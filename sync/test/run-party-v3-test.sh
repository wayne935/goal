#!/bin/bash
# 在本機 PostgreSQL 模擬 Supabase，實測 sync/party-v3.sql（一起專注）的權限。
# 用法（root 或 postgres 使用者）：bash sync/test/run-party-v3-test.sh
set -e
DIR=$(cd "$(dirname "$0")/.." && pwd); PG=/usr/lib/postgresql/16/bin; T=/tmp/goal-pgtest
if [ ! -d $T/data ]; then
  mkdir -p $T && chown postgres $T
  su postgres -s /bin/sh -c "$PG/initdb -D $T/data -U postgres -A trust >/dev/null && $PG/pg_ctl -D $T/data -o '-k $T -p 54329 -c listen_addresses=' -l $T/log start >/dev/null"
  sleep 2
fi
P="psql -h $T -p 54329 -U postgres -q"
$P -c 'drop database if exists goal_test3' -c 'create database goal_test3' >/dev/null
for r in anon authenticated; do $P -c "do \$\$ begin create role $r nologin; exception when duplicate_object then null; end \$\$" >/dev/null; done
grep -v '^create role' "$DIR/test/mock-supabase.sql" | $P -d goal_test3 -v ON_ERROR_STOP=1 >/dev/null
for f in supabase-setup party-setup party-v3 party-v3; do $P -d goal_test3 -v ON_ERROR_STOP=1 -f "$DIR/$f.sql" 2>/dev/null; done
echo '可以重複執行：OK'
P="psql -h $T -p 54329 -U postgres -d goal_test3 -qtA"
as() { local u=$1 want=$2; shift 2; local sub=""; [ "$u" != "-" ] && sub="00000000-0000-0000-0000-00000000000$u"; local role=authenticated; [ "$u" = "-" ] && role=anon
  printf '%-2s [應該 %s] %s\n     => ' "$u" "$want" "$*"; $P -c "set role $role; set request.jwt.claim.sub = '$sub'; $*" 2>&1 | sed 's/^psql:.*ERROR:  /ERROR: /;s/CONTEXT:.*//' | tr '\n' ' '; echo; }
A=00000000-0000-0000-0000-00000000000a; B=00000000-0000-0000-0000-00000000000b; C=00000000-0000-0000-0000-00000000000c; E=00000000-0000-0000-0000-00000000000e
CODE=$($P -c "set role authenticated; set request.jwt.claim.sub='$A'; select invite_code from public.goal_create_party('實驗室', 'Wayne')" | tail -1)
$P -c "set role authenticated; set request.jwt.claim.sub='$B'; select public.goal_join_party('$CODE', '小明')" >/dev/null
$P -c "set role authenticated; set request.jwt.claim.sub='$E'; select public.goal_create_party('別隊', 'Eve')" >/dev/null
echo '--- 開房'
as c 'ERROR not_in_party' "select public.goal_focus_open(25, '寫論文')"
as a 'ERROR bad_minutes' "select public.goal_focus_open(5, '寫論文')"
as a 'ERROR bad_habit' "select public.goal_focus_open(25, '   ')"
as - 'ERROR' "select public.goal_focus_open(25, '寫論文')"
ROOM=$($P -c "set role authenticated; set request.jwt.claim.sub='$A'; select public.goal_focus_open(25, '寫論文')" | tail -1); echo "room=$ROOM"
as b 'ERROR room_busy' "select public.goal_focus_open(50, '讀論文')"
as a 'ERROR' "insert into goal_focus_rooms(party_id, minutes) values (public.goal_my_party(), 30)"
as a 'ERROR' "update goal_focus_rooms set started_at = now() - interval '1 hour'"
echo '--- 開始前至少 2 人'
as a 'ERROR need_two' "select public.goal_focus_start('$ROOM') is not null"
echo '--- 加入'
as e 'ERROR' "insert into goal_focus_members(room_id, habit) values ('$ROOM', '闖入')"
as e '0' "select count(*) from goal_focus_rooms"
as e '0' "select count(*) from goal_focus_members"
as b 'ERROR' "insert into goal_focus_members(room_id, user_id, habit) values ('$ROOM', '$C', '代別人加')"
as b 'OK' "insert into goal_focus_members(room_id, habit) values ('$ROOM', '讀論文')"
as b 'ERROR' "insert into goal_focus_members(room_id, habit) values ('$ROOM', '重複加入')"
as b 'ERROR' "update goal_focus_members set state = 'evil' where user_id = '$B'"
as b 'ERROR' "update goal_focus_members set habit = '改名' where user_id = '$B'"
as b 'UPDATE 0' "update goal_focus_members set state = 'aborted' where user_id = '$A'"
as a 'Wayne 看到 2 人' "select count(*) from goal_focus_members"
echo '--- 只有開房的人能開始 / 取消'
as b 'ERROR room_closed' "select public.goal_focus_start('$ROOM') is not null"
as b 'ERROR room_closed' "select public.goal_focus_cancel('$ROOM')"
as a 'OK' "select public.goal_focus_start('$ROOM') is not null"
as a 'ERROR room_closed' "select public.goal_focus_start('$ROOM') is not null"
as a 'ERROR room_closed' "select public.goal_focus_cancel('$ROOM')"
echo '--- 開始之後不能再加入、不能退出；可以改自己的狀態'
$P -c "set role authenticated; set request.jwt.claim.sub='$C'; select public.goal_join_party('$CODE', 'C')" >/dev/null
as c 'ERROR' "insert into goal_focus_members(room_id, habit) values ('$ROOM', '遲到')"
as b 'DELETE 0（還在）' "delete from goal_focus_members where user_id = '$B'; select count(*) from goal_focus_members"
as b 'OK' "update goal_focus_members set state = 'running' where user_id = '$B'"
as b 'running' "select state from goal_focus_members where user_id = '$B'"
as a 'ERROR room_busy' "select public.goal_focus_open(25, '寫論文')"
echo '--- 結束後可以再開新房；過期的房不能開始'
$P -c "update goal_focus_rooms set started_at = now() - interval '30 minutes'" >/dev/null
R2=$($P -c "set role authenticated; set request.jwt.claim.sub='$B'; select public.goal_focus_open(50, '讀論文')" | tail -1); echo "room2=$R2"
as a 'OK' "insert into goal_focus_members(room_id, habit) values ('$R2', '寫論文')"
as a 'OK（退出）' "delete from goal_focus_members where room_id = '$R2' and user_id = '$A'"
$P -c "update goal_focus_rooms set created_at = now() - interval '20 minutes' where id = '$R2'" >/dev/null
as b 'ERROR（人數不夠或過期）' "select public.goal_focus_start('$R2') is not null"
as a 'ERROR' "insert into goal_focus_members(room_id, habit) values ('$R2', '寫論文')"
echo '--- 退出隊伍就看不到房間'
as b 'OK' "delete from goal_party_members where user_id = '$B'"
as b '0' "select count(*) from goal_focus_rooms"
