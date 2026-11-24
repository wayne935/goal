#!/bin/bash
# 在本機 PostgreSQL 模擬 Supabase，實測 sync/party-v2.sql 的權限（先跑一次 run-party-test.sh 的同一套環境）。
# 用法（root 或 postgres 使用者）：bash sync/test/run-party-v2-test.sh
# 每一行後面標了「應該」的結果，ERROR 或 0 筆代表有擋下來。
set -e
DIR=$(cd "$(dirname "$0")/.." && pwd); PG=/usr/lib/postgresql/16/bin; T=/tmp/goal-pgtest
if [ ! -d $T/data ]; then
  mkdir -p $T && chown postgres $T
  su postgres -s /bin/sh -c "$PG/initdb -D $T/data -U postgres -A trust >/dev/null && $PG/pg_ctl -D $T/data -o '-k $T -p 54329 -c listen_addresses=' -l $T/log start >/dev/null"
  sleep 2
fi
P="psql -h $T -p 54329 -U postgres -q"
$P -c 'drop database if exists goal_test2' -c 'create database goal_test2' >/dev/null
for r in anon authenticated; do $P -c "do \$\$ begin create role $r nologin; exception when duplicate_object then null; end \$\$" >/dev/null; done
grep -v '^create role' "$DIR/test/mock-supabase.sql" | $P -d goal_test2 -v ON_ERROR_STOP=1 >/dev/null
for f in supabase-setup party-setup party-v2 party-v2 party-setup; do $P -d goal_test2 -v ON_ERROR_STOP=1 -f "$DIR/$f.sql" 2>/dev/null; done
echo '可以重複執行（也可以之後再跑一次 party-setup.sql）：OK'
P="psql -h $T -p 54329 -U postgres -d goal_test2 -qtA"
as() { local u=$1 want=$2; shift 2; local sub=""; [ "$u" != "-" ] && sub="00000000-0000-0000-0000-00000000000$u"; local role=authenticated; [ "$u" = "-" ] && role=anon
  printf '%-2s [應該 %s] %s\n     => ' "$u" "$want" "$*"; $P -c "set role $role; set request.jwt.claim.sub = '$sub'; $*" 2>&1 | sed 's/^psql:.*ERROR:  /ERROR: /' | tr '\n' ' '; echo; }
A=00000000-0000-0000-0000-00000000000a; B=00000000-0000-0000-0000-00000000000b; C=00000000-0000-0000-0000-00000000000c; E=00000000-0000-0000-0000-00000000000e
CODE=$($P -c "set role authenticated; set request.jwt.claim.sub='$A'; select invite_code from public.goal_create_party('實驗室', 'Wayne')" | tail -1)
$P -c "set role authenticated; set request.jwt.claim.sub='$B'; select public.goal_join_party('$CODE', '小明')" >/dev/null
$P -c "set role authenticated; set request.jwt.claim.sub='$E'; select public.goal_create_party('別隊', 'Eve')" >/dev/null
MP="(select public.goal_my_party())"
echo '--- 加油表情'
as a 'OK'    "insert into goal_cheers(party_id, to_user, day, kind) values ($MP, '$B', current_date, 'hug')"
as a 'ERROR' "insert into goal_cheers(party_id, to_user, day, kind) values ($MP, '$B', current_date - 1, 'evil')"
as a 'OK'    "insert into goal_cheers(party_id, to_user, day) values ($MP, '$B', current_date - 1)"
as b 'hug,cheer' "select string_agg(kind, ',' order by day desc) from goal_cheers"
echo '--- 戳一下'
as a 'OK'    "insert into goal_nudges(party_id, to_user, day) values ($MP, '$B', current_date)"
as a 'ERROR' "insert into goal_nudges(party_id, to_user, day) values ($MP, '$B', current_date)"
as a 'ERROR' "insert into goal_nudges(party_id, to_user, day) values ($MP, '$E', current_date)"
as a 'ERROR' "insert into goal_nudges(party_id, from_user, to_user, day) values ($MP, '$B', '$A', current_date)"
as a 'ERROR' "insert into goal_nudges(party_id, to_user, day) values ($MP, '$A', current_date - 1)"
as a 'ERROR' "insert into goal_nudges(party_id, to_user, day) values ($MP, '$B', current_date - 5)"
as e '0 筆（讀不到別隊）' "insert into goal_nudges(party_id, to_user, day) select party_id, '$A', current_date from goal_party_members where user_id = '$A'"
as b '1'     "select count(*) from goal_nudges"
as e '0'     "select count(*) from goal_nudges"
as - 'ERROR' "select count(*) from goal_nudges"
echo '--- 送禮'
as a 'OK'    "insert into goal_gifts(party_id, to_user, day, item) values ($MP, '$B', current_date, 'coins50')"
as a 'ERROR' "insert into goal_gifts(party_id, to_user, day, item) values ($MP, '$B', current_date, 'boost')"
as a 'ERROR' "insert into goal_gifts(party_id, to_user, day, item) values ($MP, '$B', current_date - 1, 'coins9999')"
as a 'ERROR' "insert into goal_gifts(party_id, to_user, day, item) values ($MP, '$E', current_date - 1, 'freeze')"
as a 'ERROR' "insert into goal_gifts(party_id, from_user, to_user, day, item) values ($MP, '$B', '$A', current_date - 1, 'freeze')"
as a 'ERROR' "update goal_gifts set item = 'coins100'"
as b 'ERROR' "with d as (delete from goal_gifts returning 1) select count(*) from d"
as b 'coins50' "select string_agg(item, ',') from goal_gifts where to_user = '$B'"
as e '0'     "select count(*) from goal_gifts"
echo '--- 見證人'
as a 'OK'    "insert into goal_witness(contract_id, party_id, witness, week, goal_text, target, count, status) values ('ct-1', $MP, '$B', current_date, '運動 3 次', 3, 0, 'active')"
as a 'ERROR' "insert into goal_witness(contract_id, party_id, witness, week, goal_text, target) values ('ct-2', $MP, '$E', current_date, 'x', 1)"
as a 'ERROR' "insert into goal_witness(contract_id, party_id, witness, week, goal_text, target) values ('ct-3', $MP, '$A', current_date, 'x', 1)"
as a 'ERROR' "insert into goal_witness(contract_id, party_id, witness, week, goal_text, target, verdict) values ('ct-4', $MP, '$B', current_date, 'x', 1, 'ok')"
as a 'ERROR' "insert into goal_witness(contract_id, party_id, witness, week, goal_text, target) values ('ct-5', $MP, '$B', current_date - 30, 'x', 1)"
as a 'OK'    "update goal_witness set count = 3, status = 'done' where contract_id = 'ct-1'"
as a 'ERROR' "update goal_witness set verdict = 'ok' where contract_id = 'ct-1'"
as a 'ERROR' "update goal_witness set target = 1 where contract_id = 'ct-1'"
as a 'ERROR' "select public.goal_witness_verdict('$A', 'ct-1', 'ok', '')"
as b 'UPDATE 0' "update goal_witness set count = 0"
as b '1'     "select count(*) from goal_witness"
as e '0'     "select count(*) from goal_witness"
as e 'ERROR' "select public.goal_witness_verdict('$A', 'ct-1', 'reject', '')"
as b 'ERROR' "select public.goal_witness_verdict('$A', 'ct-1', 'maybe', '')"
as b 'OK'    "select public.goal_witness_verdict('$A', 'ct-1', 'reject', '  明明沒去  ')"
as b 'ERROR' "select public.goal_witness_verdict('$A', 'ct-1', 'ok', '')"
as a 'reject|明明沒去' "select verdict || '|' || note from goal_witness"
as a 'OK'    "insert into goal_witness(contract_id, party_id, witness, week, goal_text, target) values ('ct-old', $MP, '$B', current_date - 8, '舊契約', 1)"
$P -c "update goal_witness set week = current_date - 11 where contract_id = 'ct-old'" >/dev/null
as b 'ERROR' "select public.goal_witness_verdict('$A', 'ct-old', 'ok', '')"
as b 'DELETE 0' "delete from goal_witness"
as a 'DELETE 2' "delete from goal_witness"
echo '--- 聊天'
as a 'OK'    "insert into goal_party_messages(party_id, body) values ($MP, '今天誰要去健身房？')"
as a 'ERROR' "insert into goal_party_messages(party_id, body) values ($MP, '   ')"
as a 'ERROR' "insert into goal_party_messages(party_id, body) values ($MP, repeat('長', 201))"
as a 'ERROR' "insert into goal_party_messages(party_id, from_user, body) values ($MP, '$B', '假冒')"
as a 'ERROR' "insert into goal_party_messages(party_id, body, created_at) values ($MP, '改時間', now() - interval '1 day')"
as e '0 筆（讀不到別隊）' "insert into goal_party_messages(party_id, body) select party_id, '闖入' from goal_party_members where user_id = '$A'"
as b '1'     "select count(*) from goal_party_messages"
as e '0'     "select count(*) from goal_party_messages"
as b 'DELETE 0' "delete from goal_party_messages"
as a 'ERROR' "update goal_party_messages set body = '改掉'"
echo '--- 退出隊伍後讀不到聊天，見證也按不了'
as b 'OK'    "delete from goal_party_members where user_id = '$B'"
as b '0'     "select count(*) from goal_party_messages"
as a 'DELETE 1' "delete from goal_party_messages"
