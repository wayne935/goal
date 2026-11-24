-- Goal 成就板：一起專注（共同鎖機，PLAN 8.6，build 21）
-- 用法：先執行過 supabase-setup.sql、party-setup.sql（party-v2.sql 沒有也可以）。SQL Editor → New query → 整段貼上 → Run。
-- 可以重複執行，不會重複建立。
--
-- 一間「專注房」：一個人開房（選時間），隊友用手機加入、各自選要做的事，開房的人按「開始」，大家的手機一起鎖起來。
-- 雲端只存房間與每個人的狀態（加入 / 專注中 / 完成 / 緊急解鎖…）；鎖機本身和獎勵都在各自的手機上，跟平常的鎖機專注一樣。

-- 1. 資料表
create table if not exists public.goal_focus_rooms (
  id         uuid        primary key default gen_random_uuid(),
  party_id   uuid        not null references public.goal_parties(id) on delete cascade,
  host       uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  minutes    int         not null check (minutes between 15 and 120),
  created_at timestamptz not null default now(),
  started_at timestamptz,
  status     text        not null default 'open' check (status in ('open', 'started', 'cancelled'))
);
create index if not exists goal_focus_rooms_p on public.goal_focus_rooms (party_id, created_at desc);

create table if not exists public.goal_focus_members (
  room_id    uuid        not null references public.goal_focus_rooms(id) on delete cascade,
  user_id    uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  habit      text        not null check (char_length(habit) between 1 and 60),
  state      text        not null default 'joined' check (state in ('joined', 'running', 'done', 'aborted', 'cancelled', 'missed')),
  updated_at timestamptz not null default now(),
  primary key (room_id, user_id)
);
drop trigger if exists goal_focus_members_touch on public.goal_focus_members;
create trigger goal_focus_members_touch before update on public.goal_focus_members
  for each row execute function public.goal_touch_updated_at();

-- 2. 函式（security definer，search_path 清空）
-- 開房：同一隊同時只能有一間（還沒開始的房 15 分鐘後自動作廢；開始了的房到時間結束為止）
create or replace function public.goal_focus_open(p_minutes int, p_habit text)
returns uuid language plpgsql volatile security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  pid uuid := public.goal_my_party();
  rid uuid;
begin
  if uid is null then raise exception 'not_logged_in'; end if;
  if pid is null then raise exception 'not_in_party'; end if;
  if p_minutes is null or p_minutes not between 15 and 120 then raise exception 'bad_minutes'; end if;
  p_habit := btrim(coalesce(p_habit, ''));
  if char_length(p_habit) not between 1 and 60 then raise exception 'bad_habit'; end if;
  -- 鎖住隊伍那一列，兩個人同時開房時只會成功一間
  perform 1 from public.goal_parties p where p.id = pid for update;
  if exists (select 1 from public.goal_focus_rooms r where r.party_id = pid and (
       (r.status = 'open' and r.created_at > now() - interval '15 minutes')
    or (r.status = 'started' and r.started_at + make_interval(mins => r.minutes) > now()))) then
    raise exception 'room_busy';
  end if;
  insert into public.goal_focus_rooms as r (party_id, host, minutes) values (pid, uid, p_minutes) returning r.id into rid;
  insert into public.goal_focus_members (room_id, user_id, habit) values (rid, uid, p_habit);
  return rid;
end $$;

-- 開始：只有開房的人、房間還沒過期、至少 2 個人。開始時間由資料庫填
create or replace function public.goal_focus_start(p_room uuid)
returns timestamptz language plpgsql volatile security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  t   timestamptz;
begin
  if uid is null then raise exception 'not_logged_in'; end if;
  if (select count(*) from public.goal_focus_members m where m.room_id = p_room) < 2 then raise exception 'need_two'; end if;
  update public.goal_focus_rooms r set status = 'started', started_at = now()
   where r.id = p_room and r.host = uid and r.status = 'open' and r.created_at > now() - interval '15 minutes'
     and r.party_id = public.goal_my_party()
  returning r.started_at into t;
  if t is null then raise exception 'room_closed'; end if;
  return t;
end $$;

-- 取消：只有開房的人、還沒開始
create or replace function public.goal_focus_cancel(p_room uuid)
returns void language plpgsql volatile security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'not_logged_in'; end if;
  update public.goal_focus_rooms r set status = 'cancelled' where r.id = p_room and r.host = auth.uid() and r.status = 'open';
  if not found then raise exception 'room_closed'; end if;
end $$;

revoke all on function public.goal_focus_open(int, text), public.goal_focus_start(uuid), public.goal_focus_cancel(uuid) from public, anon, authenticated;
grant execute on function public.goal_focus_open(int, text), public.goal_focus_start(uuid), public.goal_focus_cancel(uuid) to authenticated;

-- 3. 權限：房間只能透過函式建立與修改；成員只能加入自己、改自己的狀態、在開始前退出
revoke all on public.goal_focus_rooms, public.goal_focus_members from public, anon, authenticated;
grant select on public.goal_focus_rooms to authenticated;
grant select, delete on public.goal_focus_members to authenticated;
grant insert (room_id, habit) on public.goal_focus_members to authenticated;
grant update (state) on public.goal_focus_members to authenticated;

-- 4. Row Level Security
alter table public.goal_focus_rooms   enable row level security;
alter table public.goal_focus_members enable row level security;

drop policy if exists "party rooms" on public.goal_focus_rooms;
create policy "party rooms" on public.goal_focus_rooms for select to authenticated
  using (party_id = (select public.goal_my_party()));

drop policy if exists "room members" on public.goal_focus_members;
create policy "room members" on public.goal_focus_members for select to authenticated
  using (exists (select 1 from public.goal_focus_rooms r where r.id = room_id and r.party_id = (select public.goal_my_party())));
drop policy if exists "join room" on public.goal_focus_members;
create policy "join room" on public.goal_focus_members for insert to authenticated
  with check (user_id = (select auth.uid())
              and exists (select 1 from public.goal_focus_rooms r where r.id = room_id and r.party_id = (select public.goal_my_party())
                          and r.status = 'open' and r.created_at > now() - interval '15 minutes'));
drop policy if exists "update own state" on public.goal_focus_members;
create policy "update own state" on public.goal_focus_members for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists "leave room" on public.goal_focus_members;
create policy "leave room" on public.goal_focus_members for delete to authenticated
  using (user_id = (select auth.uid()) and exists (select 1 from public.goal_focus_rooms r where r.id = room_id and r.status = 'open'));

-- 5. 讓 API 立刻認得新的表和函式
notify pgrst, 'reload schema';
