-- Goal 成就板：隊友副本（PLAN 8.3）
-- 用法：先執行過 supabase-setup.sql。Supabase 專案 → SQL Editor → New query → 整段貼上 → Run
-- 可以重複執行，不會重複建立。
--
-- 隱私原則：隊友讀不到彼此的 goal_events / goal_meta。
-- 每個人的網頁自己算好每日摘要寫進 goal_party_days，隊友只讀得到這張表。

-- 1. 資料表
create table if not exists public.goal_parties (
  id          uuid        primary key default gen_random_uuid(),
  name        text        not null check (char_length(name) between 1 and 30),
  invite_code text        not null unique,
  owner       uuid        references auth.users(id) on delete set null,
  created_at  timestamptz not null default now()
);

create table if not exists public.goal_party_members (
  party_id     uuid        not null references public.goal_parties(id) on delete cascade,
  user_id      uuid        not null unique references auth.users(id) on delete cascade,   -- 一人一隊
  display_name text        not null check (char_length(display_name) between 1 and 20),
  joined_at    timestamptz not null default now(),
  primary key (party_id, user_id)
);

create table if not exists public.goal_party_days (
  user_id    uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  day        date        not null,
  active     boolean     not null default false,
  cats       text[]      not null default '{}'
             check (cats <@ array['code','health','research','hobby','life']::text[] and cardinality(cats) <= 5),
  xp         int         check (xp between 0 and 5000),
  streak     int         check (streak between 0 and 100000),
  updated_at timestamptz not null default now(),
  primary key (user_id, day)
);

create table if not exists public.goal_cheers (
  id         uuid        primary key default gen_random_uuid(),
  party_id   uuid        not null references public.goal_parties(id) on delete cascade,
  from_user  uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  to_user    uuid        not null references auth.users(id) on delete cascade,
  day        date        not null,
  created_at timestamptz not null default now(),
  unique (from_user, to_user, day),
  check (from_user <> to_user)
);
create index if not exists goal_cheers_to on public.goal_cheers (to_user, created_at);

drop trigger if exists goal_party_days_touch on public.goal_party_days;
create trigger goal_party_days_touch before update on public.goal_party_days
  for each row execute function public.goal_touch_updated_at();

-- 2. 函式（security definer，search_path 清空，只給登入的人用）
-- 目前使用者的隊伍 id；RLS 政策用它，避免成員表的政策查自己造成遞迴
create or replace function public.goal_my_party()
returns uuid language sql stable security definer set search_path = '' as $$
  select m.party_id from public.goal_party_members m where m.user_id = (select auth.uid())
$$;

-- 8 碼邀請碼，排除容易看錯的 0 O 1 I
create or replace function public.goal_invite_code()
returns text language plpgsql volatile security definer set search_path = '' as $$
declare
  chars constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';   -- 32 個字元
  b bytea := decode(replace(gen_random_uuid()::text, '-', ''), 'hex');
  code text := '';
begin
  -- 跳過 uuid 第 6、8 個 byte（版本與變體位元是固定的）
  for i in 0..7 loop
    code := code || substr(chars, (get_byte(b, case when i < 6 then i else i + 4 end) % 32) + 1, 1);
  end loop;
  return code;
end $$;

create or replace function public.goal_create_party(p_name text, p_display_name text)
returns table (id uuid, invite_code text) language plpgsql volatile security definer set search_path = '' as $$
declare
  uid  uuid := auth.uid();
  pid  uuid;
  code text;
begin
  if uid is null then raise exception 'not_logged_in'; end if;
  if exists (select 1 from public.goal_party_members m where m.user_id = uid) then raise exception 'already_in_party'; end if;
  p_name := btrim(coalesce(p_name, ''));
  p_display_name := btrim(coalesce(p_display_name, ''));
  if char_length(p_name) not between 1 and 30 then raise exception 'bad_name'; end if;
  if char_length(p_display_name) not between 1 and 20 then raise exception 'bad_display_name'; end if;
  loop
    code := public.goal_invite_code();
    exit when not exists (select 1 from public.goal_parties p where p.invite_code = code);
  end loop;
  insert into public.goal_parties as p (name, invite_code, owner) values (p_name, code, uid) returning p.id into pid;
  insert into public.goal_party_members (party_id, user_id, display_name) values (pid, uid, p_display_name);
  return query select pid, code;
end $$;

create or replace function public.goal_join_party(p_code text, p_display_name text)
returns uuid language plpgsql volatile security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  pid uuid;
begin
  if uid is null then raise exception 'not_logged_in'; end if;
  if exists (select 1 from public.goal_party_members m where m.user_id = uid) then raise exception 'already_in_party'; end if;
  p_display_name := btrim(coalesce(p_display_name, ''));
  if char_length(p_display_name) not between 1 and 20 then raise exception 'bad_display_name'; end if;
  -- 鎖住隊伍那一列，兩個人同時加入時人數才不會算錯
  select p.id into pid from public.goal_parties p where p.invite_code = upper(btrim(coalesce(p_code, ''))) for update;
  if pid is null then raise exception 'party_not_found'; end if;
  if (select count(*) from public.goal_party_members m where m.party_id = pid) >= 4 then raise exception 'party_full'; end if;
  insert into public.goal_party_members (party_id, user_id, display_name) values (pid, uid, p_display_name);
  return pid;
end $$;

-- 最後一個人退出時刪掉隊伍（加油紀錄跟著刪除）
create or replace function public.goal_party_cleanup()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if not exists (select 1 from public.goal_party_members m where m.party_id = old.party_id) then
    delete from public.goal_parties p where p.id = old.party_id;
  end if;
  return null;
end $$;
drop trigger if exists goal_party_members_cleanup on public.goal_party_members;
create trigger goal_party_members_cleanup after delete on public.goal_party_members
  for each row execute function public.goal_party_cleanup();

revoke all on function public.goal_my_party(), public.goal_invite_code(), public.goal_create_party(text, text),
  public.goal_join_party(text, text), public.goal_party_cleanup() from public, anon, authenticated;
grant execute on function public.goal_my_party(), public.goal_create_party(text, text), public.goal_join_party(text, text) to authenticated;

-- 3. 權限：先全部收回（Supabase 預設會給 anon、authenticated 全部權限），再只開需要的
revoke all on public.goal_parties, public.goal_party_members, public.goal_party_days, public.goal_cheers from public, anon, authenticated;
grant select on public.goal_parties to authenticated;
grant select, delete on public.goal_party_members to authenticated;
grant update (display_name) on public.goal_party_members to authenticated;   -- 只能改顯示名稱，不能換隊
grant select, insert, update, delete on public.goal_party_days to authenticated;
grant select, insert on public.goal_cheers to authenticated;

-- 4. Row Level Security
alter table public.goal_parties       enable row level security;
alter table public.goal_party_members enable row level security;
alter table public.goal_party_days    enable row level security;
alter table public.goal_cheers        enable row level security;

-- 隊伍：只看得到自己的隊伍；建立、加入只能透過上面的函式
drop policy if exists "my party" on public.goal_parties;
create policy "my party" on public.goal_parties for select to authenticated
  using (id = (select public.goal_my_party()));

-- 成員：看得到同隊的人；只能刪（退出）或改自己那一列
drop policy if exists "party members" on public.goal_party_members;
create policy "party members" on public.goal_party_members for select to authenticated
  using (party_id = (select public.goal_my_party()));
drop policy if exists "leave party" on public.goal_party_members;
create policy "leave party" on public.goal_party_members for delete to authenticated
  using (user_id = (select auth.uid()));
drop policy if exists "rename self" on public.goal_party_members;
create policy "rename self" on public.goal_party_members for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

-- 每日摘要：只能寫自己的；看得到自己的和同隊的（退出後立刻看不到，不用刪資料）
drop policy if exists "read party days" on public.goal_party_days;
create policy "read party days" on public.goal_party_days for select to authenticated
  using (user_id = (select auth.uid())
         or exists (select 1 from public.goal_party_members m
                    where m.user_id = goal_party_days.user_id and m.party_id = (select public.goal_my_party())));
drop policy if exists "write own days" on public.goal_party_days;
create policy "write own days" on public.goal_party_days for insert to authenticated
  with check (user_id = (select auth.uid()) and day between current_date - 61 and current_date + 2);
drop policy if exists "update own days" on public.goal_party_days;
create policy "update own days" on public.goal_party_days for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and day between current_date - 61 and current_date + 2);
drop policy if exists "delete own days" on public.goal_party_days;
create policy "delete own days" on public.goal_party_days for delete to authenticated
  using (user_id = (select auth.uid()));

-- 加油：只能用自己的名義、送給同隊的人、日期在今天前後一天；看得到自己送的和收到的
drop policy if exists "send cheer" on public.goal_cheers;
create policy "send cheer" on public.goal_cheers for insert to authenticated
  with check (from_user = (select auth.uid())
              and party_id = (select public.goal_my_party())
              and exists (select 1 from public.goal_party_members m where m.user_id = to_user and m.party_id = (select public.goal_my_party()))
              and day between current_date - 1 and current_date + 1);
drop policy if exists "read cheers" on public.goal_cheers;
create policy "read cheers" on public.goal_cheers for select to authenticated
  using (to_user = (select auth.uid()) or from_user = (select auth.uid()));

-- 5. 讓 API 立刻認得新的表和函式
notify pgrst, 'reload schema';
