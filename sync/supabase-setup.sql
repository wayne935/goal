-- Goal 成就板：Supabase 資料表設定
-- 用法：Supabase 專案 → SQL Editor → New query → 整段貼上 → Run
-- 可以重複執行，不會重複建立。

-- 1. 事件表：每一筆打卡、解題、兌換都是一列（手機 widget 也直接寫這裡）
create table if not exists public.goal_events (
  user_id    uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  id         text        not null,
  ts         timestamptz not null,
  data       jsonb       not null,
  deleted    boolean     not null default false,
  updated_at timestamptz not null default now(),
  primary key (user_id, id)
);
create index if not exists goal_events_user_updated on public.goal_events (user_id, updated_at);

-- 2. 設定表：習慣、獎勵、題單、已解鎖徽章、偏好（每個使用者一列）
create table if not exists public.goal_meta (
  user_id    uuid        primary key default auth.uid() references auth.users(id) on delete cascade,
  data       jsonb       not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

-- 3. 更新時自動記錄時間（同步時只抓有變動的資料）
create or replace function public.goal_touch_updated_at()
returns trigger language plpgsql set search_path = '' as $$
begin new.updated_at = now(); return new; end $$;

drop trigger if exists goal_events_touch on public.goal_events;
create trigger goal_events_touch before update on public.goal_events
  for each row execute function public.goal_touch_updated_at();
drop trigger if exists goal_meta_touch on public.goal_meta;
create trigger goal_meta_touch before update on public.goal_meta
  for each row execute function public.goal_touch_updated_at();

-- 4. Row Level Security：只有登入的本人能讀寫自己的資料
alter table public.goal_events enable row level security;
alter table public.goal_meta   enable row level security;

drop policy if exists "own events" on public.goal_events;
create policy "own events" on public.goal_events for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists "own meta" on public.goal_meta;
create policy "own meta" on public.goal_meta for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

grant select, insert, update, delete on public.goal_events, public.goal_meta to authenticated;
revoke all on public.goal_events, public.goal_meta from anon;
