#!/bin/bash
# 在本機 PostgreSQL 模擬 Supabase，實測 sync/party-setup.sql 的權限（雲端 Claude session 裡有 PostgreSQL 16）。
# 用法（root 或 postgres 使用者）：bash sync/test/run-party-test.sh
# 輸出逐項列出每個 SQL 與結果：應該被擋的要看到 ERROR 或 0 筆。
set -e
DIR=$(cd "$(dirname "$0")/.." && pwd); PG=/usr/lib/postgresql/16/bin; T=/tmp/goal-pgtest
if [ ! -d $T/data ]; then
  mkdir -p $T && chown postgres $T
  su postgres -s /bin/sh -c "$PG/initdb -D $T/data -U postgres -A trust >/dev/null && $PG/pg_ctl -D $T/data -o '-k $T -p 54329 -c listen_addresses=' -l $T/log start >/dev/null"
  sleep 2
fi
P="psql -h $T -p 54329 -U postgres -q"
$P -c 'drop database if exists goal_test' -c 'create database goal_test' >/dev/null
for r in anon authenticated; do $P -c "do \$\$ begin create role $r nologin; exception when duplicate_object then null; end \$\$" >/dev/null; done
grep -v '^create role' "$DIR/test/mock-supabase.sql" | $P -d goal_test -v ON_ERROR_STOP=1 >/dev/null
$P -d goal_test -v ON_ERROR_STOP=1 -f "$DIR/supabase-setup.sql" 2>/dev/null
$P -d goal_test -v ON_ERROR_STOP=1 -f "$DIR/party-setup.sql" 2>/dev/null
$P -d goal_test -v ON_ERROR_STOP=1 -f "$DIR/party-setup.sql" 2>/dev/null && echo '可以重複執行：OK'
# usage: as <role> <user-letter|-> <sql>
P="psql -h /tmp/goal-pgtest -p 54329 -U postgres -d goal_test -qtA"
as() { local role=$1 u=$2; shift 2; local sub=""; [ "$u" != "-" ] && sub="00000000-0000-0000-0000-00000000000$u";
  printf '%-4s %-2s | %s\n     => ' "$role" "$u" "$*"; $P -c "set role $role; set request.jwt.claim.sub = '$sub'; $*" 2>&1 | sed 's/^psql:.*ERROR:  /ERROR: /' | tr '\n' ' '; echo; }
A=00000000-0000-0000-0000-00000000000a; B=00000000-0000-0000-0000-00000000000b; C=00000000-0000-0000-0000-00000000000c; D=00000000-0000-0000-0000-00000000000d; E=00000000-0000-0000-0000-00000000000e; F=00000000-0000-0000-0000-00000000000f
echo '--- setup: A creates party, B joins; E creates another party'
CODE=$($P -c "set role authenticated; set request.jwt.claim.sub='$A'; select invite_code from public.goal_create_party('實驗室', 'Wayne')" | tail -1)
echo "code=$CODE"
as authenticated b "select public.goal_join_party('$(echo $CODE | tr A-Z a-z)', '小明') is not null"
CODE2=$($P -c "set role authenticated; set request.jwt.claim.sub='$E'; select invite_code from public.goal_create_party('別隊', 'Eve')" | tail -1)
as authenticated a "insert into goal_party_days(day, active, cats, xp) values (current_date, true, '{code,health}', 50)"
as authenticated b "insert into goal_party_days(day, active, cats) values (current_date, true, '{life}')"
as authenticated e "insert into goal_party_days(day, active, cats) values (current_date, true, '{hobby}')"
echo '--- anon sees nothing / can do nothing'
for t in goal_parties goal_party_members goal_party_days goal_cheers goal_events; do as anon - "select count(*) from $t"; done
as anon - "select public.goal_my_party()"
as anon - "select public.goal_join_party('$CODE', 'x')"
as anon - "insert into goal_party_days(user_id, day) values ('$A', current_date)"
echo '--- not logged in (authenticated role w/o uid)'
as authenticated - "select public.goal_join_party('$CODE', 'x')"
echo '--- A sees own party only'
as authenticated a "select string_agg(name, ',') from goal_parties"
as authenticated a "select string_agg(display_name, ',' order by display_name) from goal_party_members"
as authenticated a "select string_agg(cats::text, ';' order by user_id) from goal_party_days"
echo '--- E (other party) cannot see A/B'
as authenticated e "select string_agg(name, ',') from goal_parties"
as authenticated e "select string_agg(display_name, ',') from goal_party_members"
as authenticated e "select string_agg(cats::text, ';') from goal_party_days"
echo '--- D (no party) sees only own'
as authenticated d "select count(*) from goal_parties"
as authenticated d "select count(*) from goal_party_days"
echo '--- cannot add self to other party / switch party / insert party directly'
as authenticated d "insert into goal_party_members(party_id, user_id, display_name) select id, '$D', 'x' from goal_parties"
as authenticated d "insert into goal_party_members(party_id, user_id, display_name) values ((select party_id from goal_party_members limit 1), '$D', 'x')"
as authenticated e "update goal_party_members set party_id = (select id from goal_parties where invite_code='$CODE') where user_id='$E'"
as authenticated b "update goal_party_members set user_id = '$D' where user_id='$B'"
as authenticated d "insert into goal_parties(name, invite_code) values ('x', 'AAAAAAAA')"
as authenticated a "update goal_parties set name='hacked'"
as authenticated a "delete from goal_parties"
as authenticated e "select public.goal_join_party('$CODE', 'Eve')"
echo '--- cannot touch others summaries'
as authenticated a "insert into goal_party_days(user_id, day, active) values ('$B', current_date - 1, true)"
as authenticated a "update goal_party_days set xp = 999 where user_id = '$B'"
as authenticated a "delete from goal_party_days where user_id = '$B'"
as authenticated a "select count(*) from goal_party_days where user_id = '$B' and xp = 999"
as authenticated a "insert into goal_party_days(day, cats) values (current_date - 3, '{evil}')"
as authenticated a "insert into goal_party_days(day) values (current_date - 100)"
as authenticated a "insert into goal_party_days(day, xp) values (current_date - 2, 99999)"
echo '--- rename: own only, length checked'
as authenticated a "update goal_party_members set display_name = 'Wayne W' where user_id = '$A'"
as authenticated a "update goal_party_members set display_name = 'pwned' where user_id = '$B'"
as authenticated a "select display_name from goal_party_members where user_id = '$B'"
as authenticated a "update goal_party_members set display_name = '' where user_id = '$A'"
echo '--- cheers'
as authenticated a "insert into goal_cheers(party_id, to_user, day) values (public.goal_my_party(), '$B', current_date)"
as authenticated a "insert into goal_cheers(party_id, to_user, day) values (public.goal_my_party(), '$B', current_date)"
as authenticated a "insert into goal_cheers(party_id, from_user, to_user, day) values (public.goal_my_party(), '$B', '$A', current_date - 1)"
as authenticated a "insert into goal_cheers(party_id, to_user, day) values (public.goal_my_party(), '$A', current_date - 1)"
as authenticated a "insert into goal_cheers(party_id, to_user, day) values (public.goal_my_party(), '$E', current_date)"
as authenticated a "insert into goal_cheers(party_id, to_user, day) values ((select id from goal_parties limit 1), '$B', current_date + 5)"
as authenticated e "insert into goal_cheers(party_id, to_user, day) select party_id, '$A', current_date from goal_party_members where user_id='$E'"
as authenticated b "select count(*) from goal_cheers where to_user = '$B'"
as authenticated e "select count(*) from goal_cheers"
as authenticated b "delete from goal_cheers"
echo '--- party full (max 4), already in party'
as authenticated c "select public.goal_join_party('$CODE', 'C') is not null"
as authenticated d "select public.goal_join_party('$CODE', 'D') is not null"
as authenticated f "select public.goal_join_party('$CODE', 'F')"
as authenticated a "select * from public.goal_create_party('x', 'y')"
as authenticated f "select public.goal_join_party('NOPE1234', 'F')"
as authenticated f "select public.goal_invite_code()"
echo '--- leave: B leaves -> A cannot read B days anymore; B cannot read A'
as authenticated b "delete from goal_party_members where user_id = '$A'"
as authenticated a "select count(*) from goal_party_members"
as authenticated b "delete from goal_party_members where user_id = '$B'"
as authenticated a "select count(*) from goal_party_days where user_id = '$B'"
as authenticated b "select count(*) from goal_party_days"
as authenticated b "select count(*) from goal_parties"
echo '--- last member leaving deletes party'
as authenticated e "delete from goal_party_members where user_id = '$E'"
$P -c "select count(*) as parties_left, string_agg(name, ',') from public.goal_parties"
