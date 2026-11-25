-- Goal 成就板：一起看的片、想一起看的片單（PLAN 11.3，build 24）
-- 用法：先執行過 supabase-setup.sql、party-setup.sql、party-v4.sql。SQL Editor → New query → 整段貼上 → Run。
-- 可以重複執行，不會重複建立。
--
-- 一起看的片：兩個人對同一部片（影集是同一季）各自打分數、寫心得，兩個人都寫了才看得到對方的。
--   還沒寫的那個人只看得到「對方看完哪一部了」（goal_watch_titles()，沒有分數和心得）。
-- 想一起看的片單：同隊的人都看得到、都可以加、都可以刪。
-- 自己的影劇紀錄還是存在自己的事件裡（goal_events）；這裡只放勾了「一起看的」那一筆的複本。

-- 1. 一起看的片（每人每部片一列；影集用 season 分開，電影的 season 是 0）
create table if not exists public.goal_watch_together (
  party_id   uuid        not null references public.goal_parties(id) on delete cascade,
  user_id    uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  media      text        not null check (media in ('movie', 'tv')),
  tmdb       int         not null check (tmdb between 1 and 999999999),
  season     int         not null default 0 check (season between 0 and 200),
  title      text        not null check (char_length(title) between 1 and 100),
  year       int         check (year between 1870 and 2100),
  poster     text        check (poster ~ '^/[A-Za-z0-9_-]{6,64}\.(jpg|jpeg|png|webp)$'),
  rating     int         check (rating between 1 and 10),   -- 半星制：1 = 半顆星，10 = 5 顆星
  review     text        not null default '' check (char_length(review) <= 3000),
  day        date        not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (user_id, media, tmdb, season)
);
create index if not exists goal_watch_together_p on public.goal_watch_together (party_id, media, tmdb, season);
drop trigger if exists goal_watch_together_touch on public.goal_watch_together;
create trigger goal_watch_together_touch before update on public.goal_watch_together
  for each row execute function public.goal_touch_updated_at();

-- 2. 想一起看的片單
create table if not exists public.goal_watchlist (
  id         uuid        primary key default gen_random_uuid(),
  party_id   uuid        not null references public.goal_parties(id) on delete cascade,
  added_by   uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  media      text        not null check (media in ('movie', 'tv')),
  tmdb       int         not null check (tmdb between 1 and 999999999),
  title      text        not null check (char_length(title) between 1 and 100),
  year       int         check (year between 1870 and 2100),
  poster     text        check (poster ~ '^/[A-Za-z0-9_-]{6,64}\.(jpg|jpeg|png|webp)$'),
  created_at timestamptz not null default now(),
  unique (party_id, media, tmdb)
);

-- 離開隊伍時，收回自己「一起看的」心得（片單是兩個人的，留給還在隊上的人）
create or replace function public.goal_watch_leave()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  delete from public.goal_watch_together w where w.user_id = old.user_id and w.party_id = old.party_id;
  return null;
end $$;
drop trigger if exists goal_party_members_leave_watch on public.goal_party_members;
create trigger goal_party_members_leave_watch after delete on public.goal_party_members
  for each row execute function public.goal_watch_leave();

-- 3. 函式（security definer，search_path 清空）
-- 我寫過這部片（同一季）了沒（看對方心得的政策用它；直接在政策裡查同一張表會遞迴）
create or replace function public.goal_watched(p_media text, p_tmdb int, p_season int)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.goal_watch_together w
                 where w.user_id = (select auth.uid()) and w.media = p_media and w.tmdb = p_tmdb and w.season = p_season)
$$;

-- 隊上的人看完了哪些片：只有片名、海報、日期，沒有分數和心得（還沒寫的人用它知道「對方在等你」）
create or replace function public.goal_watch_titles()
returns table (user_id uuid, media text, tmdb int, season int, title text, year int, poster text, day date, created_at timestamptz)
language sql stable security definer set search_path = '' as $$
  select w.user_id, w.media, w.tmdb, w.season, w.title, w.year, w.poster, w.day, w.created_at
  from public.goal_watch_together w
  where w.party_id = public.goal_my_party()
    and exists (select 1 from public.goal_party_members m where m.user_id = w.user_id and m.party_id = w.party_id)
  order by w.created_at desc
  limit 200
$$;

revoke all on function public.goal_watched(text, int, int), public.goal_watch_titles(), public.goal_watch_leave() from public, anon, authenticated;
grant execute on function public.goal_watched(text, int, int), public.goal_watch_titles() to authenticated;

-- 4. 權限：先全部收回，再只開需要的欄位
revoke all on public.goal_watch_together, public.goal_watchlist from public, anon, authenticated;
grant select, delete on public.goal_watch_together to authenticated;
grant insert (party_id, media, tmdb, season, title, year, poster, rating, review, day) on public.goal_watch_together to authenticated;
grant update (party_id, media, tmdb, season, title, year, poster, rating, review, day) on public.goal_watch_together to authenticated;   -- 網頁用 upsert（on_conflict=user_id,media,tmdb,season）覆蓋；政策限制只能留在自己的隊伍
grant select, delete on public.goal_watchlist to authenticated;
grant insert (party_id, media, tmdb, title, year, poster) on public.goal_watchlist to authenticated;

-- 5. Row Level Security
alter table public.goal_watch_together enable row level security;
alter table public.goal_watchlist      enable row level security;

-- 一起看的片：自己的隨時看得到；同隊的人的，要自己也寫了同一部（同一季）才看得到
drop policy if exists "read watch together" on public.goal_watch_together;
create policy "read watch together" on public.goal_watch_together for select to authenticated
  using (user_id = (select auth.uid())
         or (party_id = (select public.goal_my_party()) and public.goal_watched(media, tmdb, season)
             and exists (select 1 from public.goal_party_members m where m.user_id = goal_watch_together.user_id and m.party_id = (select public.goal_my_party()))));
drop policy if exists "write watch together" on public.goal_watch_together;
create policy "write watch together" on public.goal_watch_together for insert to authenticated
  with check (user_id = (select auth.uid()) and party_id = (select public.goal_my_party()) and day between date '1900-01-01' and current_date + 1);
drop policy if exists "edit own watch together" on public.goal_watch_together;
create policy "edit own watch together" on public.goal_watch_together for update to authenticated
  using (user_id = (select auth.uid()) and party_id = (select public.goal_my_party()))
  with check (user_id = (select auth.uid()) and party_id = (select public.goal_my_party()) and day between date '1900-01-01' and current_date + 1);
drop policy if exists "delete own watch together" on public.goal_watch_together;
create policy "delete own watch together" on public.goal_watch_together for delete to authenticated
  using (user_id = (select auth.uid()));

-- 片單：同隊看得到；只能用自己的名義加到自己的隊伍；同隊的人都可以刪（看完了、不想看了）
drop policy if exists "party watchlist" on public.goal_watchlist;
create policy "party watchlist" on public.goal_watchlist for select to authenticated
  using (party_id = (select public.goal_my_party()));
drop policy if exists "add watchlist" on public.goal_watchlist;
create policy "add watchlist" on public.goal_watchlist for insert to authenticated
  with check (added_by = (select auth.uid()) and party_id = (select public.goal_my_party()));
drop policy if exists "remove watchlist" on public.goal_watchlist;
create policy "remove watchlist" on public.goal_watchlist for delete to authenticated
  using (party_id = (select public.goal_my_party()));

-- 6. 讓 API 立刻認得新的表和函式
notify pgrst, 'reload schema';
