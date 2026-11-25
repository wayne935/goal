-- Goal 成就板：情侶隊與交換日記（PLAN 11，build 23）
-- 用法：先執行過 supabase-setup.sql、party-setup.sql（v2、v3 沒有也可以）。SQL Editor → New query → 整段貼上 → Run。
-- 可以重複執行，不會重複建立。
--
-- 隊伍多了類型（friends 朋友隊 / couple 情侶隊，情侶隊最多 2 人）和紀念日。
-- 交換日記：自己的日記是加密的，只有「分享給對方」的那篇會把內容複製一份到 goal_exchange，只有同隊的人讀得到。
-- 每日一問：兩個人都回答了才看得到對方的答案。
-- 心情：goal_party_days 多一欄 mood（網頁只在情侶隊、而且設定裡沒關掉時上傳）。

-- 1. 隊伍類型、紀念日、每日心情
alter table public.goal_parties add column if not exists kind text not null default 'friends';
alter table public.goal_parties add column if not exists since date;
alter table public.goal_parties drop constraint if exists goal_parties_kind_check;
alter table public.goal_parties add constraint goal_parties_kind_check check (kind in ('friends', 'couple'));
alter table public.goal_party_days add column if not exists mood int;
alter table public.goal_party_days drop constraint if exists goal_party_days_mood_check;
alter table public.goal_party_days add constraint goal_party_days_mood_check check (mood between 1 and 5);

-- 2. 交換日記與回覆
create table if not exists public.goal_exchange (
  id         uuid        primary key default gen_random_uuid(),
  party_id   uuid        not null references public.goal_parties(id) on delete cascade,
  author     uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  entry_id   text        not null check (char_length(entry_id) between 1 and 60),   -- 作者那邊的 journal 事件 id（改內容時用來覆蓋）
  day        date        not null,
  mood       int         check (mood between 1 and 5),
  title      text        not null default '' check (char_length(title) <= 60),
  body       text        not null check (char_length(body) between 1 and 5000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (author, entry_id)
);
create index if not exists goal_exchange_p on public.goal_exchange (party_id, created_at desc);
drop trigger if exists goal_exchange_touch on public.goal_exchange;
create trigger goal_exchange_touch before update on public.goal_exchange
  for each row execute function public.goal_touch_updated_at();

create table if not exists public.goal_exchange_replies (
  id         uuid        primary key default gen_random_uuid(),
  entry      uuid        not null references public.goal_exchange(id) on delete cascade,
  author     uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  body       text        not null default '' check (char_length(body) <= 500),
  emoji      text        check (emoji in ('❤️', '😂', '🥺', '👍', '🤗', '😮')),
  created_at timestamptz not null default now(),
  check (char_length(btrim(body)) > 0 or emoji is not null)
);
create index if not exists goal_exchange_replies_e on public.goal_exchange_replies (entry, created_at);

-- 3. 每日一問（題目由網頁照日期挑，兩人同一題；qid 是題號）
create table if not exists public.goal_daily_answers (
  party_id   uuid        not null references public.goal_parties(id) on delete cascade,
  user_id    uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  day        date        not null,
  qid        int         not null check (qid between 0 and 999),
  body       text        not null check (char_length(btrim(body)) between 1 and 300),
  created_at timestamptz not null default now(),
  primary key (user_id, day)
);
create index if not exists goal_daily_answers_p on public.goal_daily_answers (party_id, day);

-- 離開隊伍時，收回自己分享的交換日記、回覆和每日一問的答案（對方就看不到了；自己的原始日記還在自己的紀錄裡）
create or replace function public.goal_exchange_leave()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  delete from public.goal_exchange x where x.author = old.user_id and x.party_id = old.party_id;
  delete from public.goal_exchange_replies r where r.author = old.user_id
    and exists (select 1 from public.goal_exchange x where x.id = r.entry and x.party_id = old.party_id);
  delete from public.goal_daily_answers a where a.user_id = old.user_id and a.party_id = old.party_id;
  return null;
end $$;
drop trigger if exists goal_party_members_leave on public.goal_party_members;
create trigger goal_party_members_leave after delete on public.goal_party_members
  for each row execute function public.goal_exchange_leave();

-- 4. 函式（security definer，search_path 清空）
-- 我在這一天回答過了沒（看對方答案的政策用它；直接在政策裡查同一張表會遞迴）
create or replace function public.goal_answered(p_day date)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.goal_daily_answers a where a.user_id = (select auth.uid()) and a.day = p_day)
$$;

-- 建立有類型的隊伍
create or replace function public.goal_create_party_kind(p_name text, p_display_name text, p_kind text)
returns table (id uuid, invite_code text) language plpgsql volatile security definer set search_path = '' as $$
declare
  r record;
begin
  if p_kind is null or p_kind not in ('friends', 'couple') then raise exception 'bad_kind'; end if;
  select * into r from public.goal_create_party(p_name, p_display_name);
  update public.goal_parties p set kind = p_kind where p.id = r.id;
  return query select r.id, r.invite_code;
end $$;

-- 加入：情侶隊最多 2 人，朋友隊最多 4 人（取代 party-setup.sql 的版本；之後重跑 party-setup.sql 的話，這個檔案也要再跑一次）
create or replace function public.goal_join_party(p_code text, p_display_name text)
returns uuid language plpgsql volatile security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  pid uuid;
  k   text;
begin
  if uid is null then raise exception 'not_logged_in'; end if;
  if exists (select 1 from public.goal_party_members m where m.user_id = uid) then raise exception 'already_in_party'; end if;
  p_display_name := btrim(coalesce(p_display_name, ''));
  if char_length(p_display_name) not between 1 and 20 then raise exception 'bad_display_name'; end if;
  select p.id, p.kind into pid, k from public.goal_parties p where p.invite_code = upper(btrim(coalesce(p_code, ''))) for update;
  if pid is null then raise exception 'party_not_found'; end if;
  if (select count(*) from public.goal_party_members m where m.party_id = pid) >= (case when k = 'couple' then 2 else 4 end) then raise exception 'party_full'; end if;
  insert into public.goal_party_members (party_id, user_id, display_name) values (pid, uid, p_display_name);
  return pid;
end $$;

-- 改隊伍類型（隊上任何人都可以；改成情侶隊時最多 2 人）
create or replace function public.goal_set_party_kind(p_kind text)
returns void language plpgsql volatile security definer set search_path = '' as $$
declare
  pid uuid := public.goal_my_party();
begin
  if auth.uid() is null then raise exception 'not_logged_in'; end if;
  if pid is null then raise exception 'not_in_party'; end if;
  if p_kind is null or p_kind not in ('friends', 'couple') then raise exception 'bad_kind'; end if;
  perform 1 from public.goal_parties p where p.id = pid for update;
  if p_kind = 'couple' and (select count(*) from public.goal_party_members m where m.party_id = pid) > 2 then raise exception 'too_many_for_couple'; end if;
  update public.goal_parties p set kind = p_kind where p.id = pid;
end $$;

-- 設定紀念日（null = 清掉）
create or replace function public.goal_set_party_since(p_since date)
returns void language plpgsql volatile security definer set search_path = '' as $$
declare
  pid uuid := public.goal_my_party();
begin
  if auth.uid() is null then raise exception 'not_logged_in'; end if;
  if pid is null then raise exception 'not_in_party'; end if;
  if p_since is not null and (p_since < date '1950-01-01' or p_since > current_date + 1) then raise exception 'bad_since'; end if;
  update public.goal_parties p set since = p_since where p.id = pid;
end $$;

revoke all on function public.goal_answered(date), public.goal_create_party_kind(text, text, text), public.goal_join_party(text, text),
  public.goal_set_party_kind(text), public.goal_set_party_since(date), public.goal_exchange_leave() from public, anon, authenticated;
grant execute on function public.goal_answered(date), public.goal_create_party_kind(text, text, text), public.goal_join_party(text, text),
  public.goal_set_party_kind(text), public.goal_set_party_since(date) to authenticated;

-- 5. 權限：先全部收回，再只開需要的欄位
revoke all on public.goal_exchange, public.goal_exchange_replies, public.goal_daily_answers from public, anon, authenticated;
grant select, delete on public.goal_exchange to authenticated;
grant insert (party_id, entry_id, day, mood, title, body) on public.goal_exchange to authenticated;
grant update (party_id, entry_id, day, mood, title, body) on public.goal_exchange to authenticated;   -- 網頁用 upsert（on_conflict=author,entry_id）覆蓋；政策限制只能留在自己的隊伍
grant select, delete on public.goal_exchange_replies to authenticated;
grant insert (entry, body, emoji) on public.goal_exchange_replies to authenticated;
grant select on public.goal_daily_answers to authenticated;
grant insert (party_id, day, qid, body) on public.goal_daily_answers to authenticated;   -- 不能改、不能刪：看過對方的答案後不能回頭改

-- 6. Row Level Security
alter table public.goal_exchange         enable row level security;
alter table public.goal_exchange_replies enable row level security;
alter table public.goal_daily_answers    enable row level security;

-- 交換日記：同隊看得到；只能用自己的名義分享到自己的隊伍；只能改、刪自己的
drop policy if exists "party exchange" on public.goal_exchange;
create policy "party exchange" on public.goal_exchange for select to authenticated
  using (party_id = (select public.goal_my_party()));
drop policy if exists "share exchange" on public.goal_exchange;
create policy "share exchange" on public.goal_exchange for insert to authenticated
  with check (author = (select auth.uid()) and party_id = (select public.goal_my_party()) and day between date '2000-01-01' and current_date + 1);
drop policy if exists "edit own exchange" on public.goal_exchange;
create policy "edit own exchange" on public.goal_exchange for update to authenticated
  using (author = (select auth.uid()) and party_id = (select public.goal_my_party()))
  with check (author = (select auth.uid()) and party_id = (select public.goal_my_party()) and day between date '2000-01-01' and current_date + 1);
drop policy if exists "unshare exchange" on public.goal_exchange;
create policy "unshare exchange" on public.goal_exchange for delete to authenticated
  using (author = (select auth.uid()));

-- 回覆：看得到同隊日記底下的回覆；只能用自己的名義回覆同隊的日記；只能刪自己的
drop policy if exists "party replies" on public.goal_exchange_replies;
create policy "party replies" on public.goal_exchange_replies for select to authenticated
  using (exists (select 1 from public.goal_exchange x where x.id = entry and x.party_id = (select public.goal_my_party())));
drop policy if exists "reply exchange" on public.goal_exchange_replies;
create policy "reply exchange" on public.goal_exchange_replies for insert to authenticated
  with check (author = (select auth.uid())
              and exists (select 1 from public.goal_exchange x where x.id = entry and x.party_id = (select public.goal_my_party())));
drop policy if exists "delete own reply" on public.goal_exchange_replies;
create policy "delete own reply" on public.goal_exchange_replies for delete to authenticated
  using (author = (select auth.uid()));

-- 每日一問：自己的答案隨時看得到；同隊的人的答案，要自己那天也回答了才看得到
drop policy if exists "read answers" on public.goal_daily_answers;
create policy "read answers" on public.goal_daily_answers for select to authenticated
  using (user_id = (select auth.uid())
         or (party_id = (select public.goal_my_party()) and public.goal_answered(day)
             and exists (select 1 from public.goal_party_members m where m.user_id = goal_daily_answers.user_id and m.party_id = (select public.goal_my_party()))));
drop policy if exists "answer" on public.goal_daily_answers;
create policy "answer" on public.goal_daily_answers for insert to authenticated
  with check (user_id = (select auth.uid()) and party_id = (select public.goal_my_party()) and day between current_date - 1 and current_date + 1);

-- 7. 讓 API 立刻認得新的表和函式
notify pgrst, 'reload schema';
