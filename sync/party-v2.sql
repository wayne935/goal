-- Goal 成就板：隊伍擴充第二輪（PLAN 8.5，build 20）
-- 用法：先執行過 supabase-setup.sql 和 party-setup.sql。Supabase 專案 → SQL Editor → New query → 整段貼上 → Run
-- 可以重複執行，不會重複建立。
--
-- 新增：加油表情（goal_cheers.kind）、戳一下（goal_nudges）、送禮（goal_gifts）、
--       契約見證人（goal_witness + goal_witness_verdict()）、隊伍聊天（goal_party_messages）。
-- 隱私原則照舊：隊友讀不到彼此的 goal_events / goal_meta；見證人只看得到你自己選擇分享的那份契約。

-- 1. 加油可以選表情（舊版網頁送的加油用預設值 cheer）
alter table public.goal_cheers add column if not exists kind text not null default 'cheer';
alter table public.goal_cheers drop constraint if exists goal_cheers_kind_check;
alter table public.goal_cheers add constraint goal_cheers_kind_check check (kind in ('cheer', 'fire', 'clap', 'hug', 'coffee', 'flex'));

-- 2. 戳一下：每人每天對同一個隊友 1 次
create table if not exists public.goal_nudges (
  id         uuid        primary key default gen_random_uuid(),
  party_id   uuid        not null references public.goal_parties(id) on delete cascade,
  from_user  uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  to_user    uuid        not null references auth.users(id) on delete cascade,
  day        date        not null,
  created_at timestamptz not null default now(),
  unique (from_user, to_user, day),
  check (from_user <> to_user)
);
create index if not exists goal_nudges_to on public.goal_nudges (to_user, created_at);

-- 3. 送禮：每人每天送 1 份（代幣在送禮的人本機扣，收到的人本機入帳）
create table if not exists public.goal_gifts (
  id         uuid        primary key default gen_random_uuid(),
  party_id   uuid        not null references public.goal_parties(id) on delete cascade,
  from_user  uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  to_user    uuid        not null references auth.users(id) on delete cascade,
  day        date        not null,
  item       text        not null check (item in ('coins20', 'coins50', 'coins100', 'freeze', 'boost')),
  created_at timestamptz not null default now(),
  unique (from_user, day),
  check (from_user <> to_user)
);
create index if not exists goal_gifts_to on public.goal_gifts (to_user, created_at);

-- 4. 契約見證人：簽約的人上傳契約摘要與進度；見證人只能透過 goal_witness_verdict() 按「確認 / 不算」，而且只能按一次
create table if not exists public.goal_witness (
  owner       uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  contract_id text        not null check (char_length(contract_id) between 1 and 60),
  party_id    uuid        not null references public.goal_parties(id) on delete cascade,
  witness     uuid        not null references auth.users(id) on delete cascade,
  week        date        not null,
  goal_text   text        not null check (char_length(goal_text) between 1 and 80),
  target      int         not null check (target between 0 and 1000),
  count       int         not null default 0 check (count between 0 and 100000),
  status      text        not null default 'active' check (status in ('active', 'done', 'failed')),
  verdict     text        not null default 'pending' check (verdict in ('pending', 'ok', 'reject')),
  verdict_at  timestamptz,
  note        text        check (char_length(note) <= 100),
  updated_at  timestamptz not null default now(),
  primary key (owner, contract_id),
  check (owner <> witness)
);
create index if not exists goal_witness_w on public.goal_witness (witness, week);
drop trigger if exists goal_witness_touch on public.goal_witness;
create trigger goal_witness_touch before update on public.goal_witness
  for each row execute function public.goal_touch_updated_at();

-- 5. 隊伍聊天：留言 1～200 字，只看得到自己隊伍的
create table if not exists public.goal_party_messages (
  id         uuid        primary key default gen_random_uuid(),
  party_id   uuid        not null references public.goal_parties(id) on delete cascade,
  from_user  uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  body       text        not null check (char_length(btrim(body)) between 1 and 200),
  created_at timestamptz not null default now()
);
create index if not exists goal_party_messages_p on public.goal_party_messages (party_id, created_at desc);

-- 6. 見證人按「確認 / 不算」：只有被指定的見證人、同一隊、還沒按過、契約那週的週一起 10 天內
create or replace function public.goal_witness_verdict(p_owner uuid, p_contract text, p_verdict text, p_note text)
returns void language plpgsql volatile security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  n   int;
begin
  if uid is null then raise exception 'not_logged_in'; end if;
  if p_verdict is null or p_verdict not in ('ok', 'reject') then raise exception 'bad_verdict'; end if;
  update public.goal_witness w
     set verdict = p_verdict, verdict_at = now(), note = nullif(left(btrim(coalesce(p_note, '')), 100), '')
   where w.owner = p_owner and w.contract_id = p_contract and w.witness = uid and w.verdict = 'pending'
     and w.party_id = public.goal_my_party() and current_date <= w.week + 10;
  get diagnostics n = row_count;
  if n = 0 then raise exception 'verdict_closed'; end if;
end $$;
revoke all on function public.goal_witness_verdict(uuid, text, text, text) from public, anon, authenticated;
grant execute on function public.goal_witness_verdict(uuid, text, text, text) to authenticated;

-- 7. 權限：先全部收回（Supabase 預設會給 anon、authenticated 全部權限），再只開需要的欄位
revoke all on public.goal_nudges, public.goal_gifts, public.goal_witness, public.goal_party_messages from public, anon, authenticated;
grant select, insert (party_id, to_user, day) on public.goal_nudges to authenticated;
grant select, insert (party_id, to_user, day, item) on public.goal_gifts to authenticated;
grant select, delete on public.goal_witness to authenticated;
grant insert (contract_id, party_id, witness, week, goal_text, target, count, status) on public.goal_witness to authenticated;
grant update (count, status) on public.goal_witness to authenticated;   -- 見證結果只能用上面的函式改
grant select, delete on public.goal_party_messages to authenticated;
grant insert (party_id, body) on public.goal_party_messages to authenticated;   -- 寄件人、時間由資料庫填，不能偽造

-- 8. Row Level Security
alter table public.goal_nudges         enable row level security;
alter table public.goal_gifts          enable row level security;
alter table public.goal_witness        enable row level security;
alter table public.goal_party_messages enable row level security;

-- 戳一下、送禮：只能用自己的名義、送給同隊的人、日期在今天前後一天；看得到自己送的和收到的
drop policy if exists "send nudge" on public.goal_nudges;
create policy "send nudge" on public.goal_nudges for insert to authenticated
  with check (from_user = (select auth.uid())
              and party_id = (select public.goal_my_party())
              and exists (select 1 from public.goal_party_members m where m.user_id = to_user and m.party_id = (select public.goal_my_party()))
              and day between current_date - 1 and current_date + 1);
drop policy if exists "read nudges" on public.goal_nudges;
create policy "read nudges" on public.goal_nudges for select to authenticated
  using (to_user = (select auth.uid()) or from_user = (select auth.uid()));

drop policy if exists "send gift" on public.goal_gifts;
create policy "send gift" on public.goal_gifts for insert to authenticated
  with check (from_user = (select auth.uid())
              and party_id = (select public.goal_my_party())
              and exists (select 1 from public.goal_party_members m where m.user_id = to_user and m.party_id = (select public.goal_my_party()))
              and day between current_date - 1 and current_date + 1);
drop policy if exists "read gifts" on public.goal_gifts;
create policy "read gifts" on public.goal_gifts for select to authenticated
  using (to_user = (select auth.uid()) or from_user = (select auth.uid()));

-- 見證：簽約的人建立（見證人要同隊、契約是最近的）、更新進度、刪除（取消契約）；見證人和簽約的人看得到
drop policy if exists "owner adds witness" on public.goal_witness;
create policy "owner adds witness" on public.goal_witness for insert to authenticated
  with check (owner = (select auth.uid())
              and party_id = (select public.goal_my_party())
              and exists (select 1 from public.goal_party_members m where m.user_id = witness and m.party_id = (select public.goal_my_party()))
              and week between current_date - 8 and current_date + 1);
drop policy if exists "owner updates progress" on public.goal_witness;
create policy "owner updates progress" on public.goal_witness for update to authenticated
  using (owner = (select auth.uid())) with check (owner = (select auth.uid()));
drop policy if exists "owner deletes" on public.goal_witness;
create policy "owner deletes" on public.goal_witness for delete to authenticated
  using (owner = (select auth.uid()));
drop policy if exists "read witness" on public.goal_witness;
create policy "read witness" on public.goal_witness for select to authenticated
  using (owner = (select auth.uid()) or witness = (select auth.uid()));

-- 聊天：同隊才看得到、只能用自己的名義發到自己的隊伍、只能刪自己的
drop policy if exists "read messages" on public.goal_party_messages;
create policy "read messages" on public.goal_party_messages for select to authenticated
  using (party_id = (select public.goal_my_party()));
drop policy if exists "post message" on public.goal_party_messages;
create policy "post message" on public.goal_party_messages for insert to authenticated
  with check (from_user = (select auth.uid()) and party_id = (select public.goal_my_party()));
drop policy if exists "delete own message" on public.goal_party_messages;
create policy "delete own message" on public.goal_party_messages for delete to authenticated
  using (from_user = (select auth.uid()));

-- 9. 讓 API 立刻認得新的表和函式
notify pgrst, 'reload schema';
