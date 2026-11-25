#!/bin/bash
# 在本機 PostgreSQL 模擬 Supabase，實測 sync/party-v4.sql（情侶隊、交換日記、每日一問）的權限。
# 用法（root 或 postgres 使用者）：bash sync/test/run-party-v4-test.sh
set -e
DIR=$(cd "$(dirname "$0")/.." && pwd); PG=/usr/lib/postgresql/16/bin; T=/tmp/goal-pgtest
if [ ! -d $T/data ]; then
  mkdir -p $T && chown postgres $T
  su postgres -s /bin/sh -c "$PG/initdb -D $T/data -U postgres -A trust >/dev/null && $PG/pg_ctl -D $T/data -o '-k $T -p 54329 -c listen_addresses=' -l $T/log start >/dev/null"
  sleep 2
fi
P="psql -h $T -p 54329 -U postgres -q"
$P -c 'drop database if exists goal_test4' -c 'create database goal_test4' >/dev/null
for r in anon authenticated; do $P -c "do \$\$ begin create role $r nologin; exception when duplicate_object then null; end \$\$" >/dev/null; done
grep -v '^create role' "$DIR/test/mock-supabase.sql" | $P -d goal_test4 -v ON_ERROR_STOP=1 >/dev/null
for f in supabase-setup party-setup party-v2 party-v3 party-v4 party-v4; do $P -d goal_test4 -v ON_ERROR_STOP=1 -f "$DIR/$f.sql" 2>/dev/null; done
echo '可以重複執行：OK'
P="psql -h $T -p 54329 -U postgres -d goal_test4 -qtA"
as() { local u=$1 want=$2; shift 2; local sub=""; [ "$u" != "-" ] && sub="00000000-0000-0000-0000-00000000000$u"; local role=authenticated; [ "$u" = "-" ] && role=anon
  printf '%-2s [應該 %s] %s\n     => ' "$u" "$want" "$*"; $P -c "set role $role; set request.jwt.claim.sub = '$sub'; $*" 2>&1 | sed 's/^psql:.*ERROR:  /ERROR: /;s/CONTEXT:.*//' | tr '\n' ' '; echo; }
val() { local u=$1; shift; $P -c "set role authenticated; set request.jwt.claim.sub='00000000-0000-0000-0000-00000000000$u'; $*" | tail -1; }
A=00000000-0000-0000-0000-00000000000a; B=00000000-0000-0000-0000-00000000000b; C=00000000-0000-0000-0000-00000000000c; E=00000000-0000-0000-0000-00000000000e

echo '--- 舊的隊伍預設是朋友隊；建立時可以選情侶隊'
OLD=$(val c "select invite_code from public.goal_create_party('舊隊', 'C')")
as c 'friends' "select kind from goal_parties"
as a 'ERROR bad_kind' "select 1 from public.goal_create_party_kind('我們', 'Wayne', 'evil')"
as - 'ERROR' "select 1 from public.goal_create_party_kind('我們', 'Wayne', 'couple')"
CODE=$(val a "select invite_code from public.goal_create_party_kind('我們', 'Wayne', 'couple')")
as a 'couple' "select kind from goal_parties"
as a 'ERROR already_in_party' "select 1 from public.goal_create_party_kind('再一隊', 'Wayne', 'friends')"
as a 'ERROR' "update goal_parties set kind = 'friends'"
echo '--- 情侶隊最多 2 人'
as b 'OK' "select public.goal_join_party('$CODE', '小美') is not null"
as e 'ERROR party_full' "select public.goal_join_party('$CODE', 'Eve')"
echo '--- 朋友隊還是 4 人；超過 2 人不能改成情侶隊'
$P -c "set role authenticated; set request.jwt.claim.sub='00000000-0000-0000-0000-00000000000d'; select public.goal_join_party('$OLD', 'D')" >/dev/null
$P -c "set role authenticated; set request.jwt.claim.sub='00000000-0000-0000-0000-00000000000f'; select public.goal_join_party('$OLD', 'F')" >/dev/null
as c 'ERROR too_many_for_couple' "select public.goal_set_party_kind('couple')"
as c 'ERROR bad_kind' "select public.goal_set_party_kind(null)"
as e 'ERROR not_in_party' "select public.goal_set_party_kind('couple')"
as a 'OK friends' "select public.goal_set_party_kind('friends'); select kind from goal_parties"
as b 'OK couple' "select public.goal_set_party_kind('couple'); select kind from goal_parties"
echo '--- 紀念日'
as a 'ERROR bad_since' "select public.goal_set_party_since(current_date + 10)"
as a 'ERROR bad_since' "select public.goal_set_party_since('1900-01-01')"
as a 'OK 2024-02-14' "select public.goal_set_party_since('2024-02-14'); select since from goal_parties"
as e 'ERROR not_in_party' "select public.goal_set_party_since('2024-02-14')"
as c 'C 隊沒有紀念日' "select coalesce(since::text, 'null') from goal_parties"
as a 'ERROR' "update goal_parties set since = '2020-01-01'"
echo '--- 心情（goal_party_days.mood）'
as a 'OK' "insert into goal_party_days(day, active, mood) values (current_date, true, 4)"
as a 'ERROR' "insert into goal_party_days(day, active, mood) values (current_date - 1, true, 9)"
as b '4' "select mood from goal_party_days where user_id = '$A'"
as c '0' "select count(*) from goal_party_days where user_id = '$A'"

echo '--- 交換日記：分享'
PA=$(val a "select public.goal_my_party()"); PC=$(val c "select public.goal_my_party()")
as a 'OK' "insert into goal_exchange(party_id, entry_id, day, mood, title, body) values ('$PA', 'j1', current_date, 5, '第一篇', '今天很開心')"
as a 'ERROR 重複 entry_id' "insert into goal_exchange(party_id, entry_id, day, body) values ('$PA', 'j1', current_date, '重複')"
as a 'ERROR 冒名' "insert into goal_exchange(party_id, author, entry_id, day, body) values ('$PA', '$B', 'j2', current_date, '冒名')"
as e 'ERROR 別隊' "insert into goal_exchange(party_id, entry_id, day, body) values ('$PA', 'x1', current_date, '闖入')"
as a 'ERROR 空內容' "insert into goal_exchange(party_id, entry_id, day, body) values ('$PA', 'j3', current_date, '')"
as a 'ERROR 太長' "insert into goal_exchange(party_id, entry_id, day, body) values ('$PA', 'j3', current_date, repeat('字', 5001))"
as a 'ERROR 未來' "insert into goal_exchange(party_id, entry_id, day, body) values ('$PA', 'j3', current_date + 5, '未來')"
as a 'ERROR 心情' "insert into goal_exchange(party_id, entry_id, day, mood, body) values ('$PA', 'j3', current_date, 6, '心情')"
as a 'OK 舊日記' "insert into goal_exchange(party_id, entry_id, day, body) values ('$PA', 'j0', '2023-05-01', '很久以前')"
as b 'OK' "insert into goal_exchange(party_id, entry_id, day, mood, body) values ('$PA', 'k1', current_date, 3, '小美的日記')"
as b '3 篇' "select count(*) from goal_exchange"
as c '0 篇' "select count(*) from goal_exchange"
as e '0 篇' "select count(*) from goal_exchange"
as - 'ERROR' "select count(*) from goal_exchange"
echo '--- 交換日記：修改、收回'
as a 'j1' "update goal_exchange set body = '改過了', title = '新標題' where entry_id = 'j1' returning entry_id"
as b '（空的，不能改別人的）' "update goal_exchange set body = '亂改' where entry_id = 'j1' returning entry_id"
as a 'ERROR 不能換隊' "update goal_exchange set party_id = '$PC' where entry_id = 'j1'"
as a 'ERROR 不能移到未來' "update goal_exchange set day = current_date + 9 where entry_id = 'j1'"
as a 'ERROR 不能換作者' "update goal_exchange set author = '$B' where entry_id = 'j1'"
as a 'ERROR 不能改時間' "update goal_exchange set created_at = now() - interval '1 year' where entry_id = 'j1'"
as a 'upsert（跟 PostgREST 一樣更新全部欄位）' "insert into goal_exchange(party_id, entry_id, day, mood, title, body) values ('$PA', 'j1', current_date, 4, 't', 'upsert') on conflict (author, entry_id) do update set party_id = excluded.party_id, entry_id = excluded.entry_id, day = excluded.day, mood = excluded.mood, title = excluded.title, body = excluded.body returning body"
as b 'ERROR 不能用 upsert 蓋掉別人的' "insert into goal_exchange(party_id, author, entry_id, day, body) values ('$PA', '$A', 'j1', current_date, 'hack') on conflict (author, entry_id) do update set body = excluded.body"
as b '（空的，不能刪別人的）' "delete from goal_exchange where entry_id = 'j1' returning entry_id"
as a 'j0' "delete from goal_exchange where entry_id = 'j0' returning entry_id"
J1=$(val a "select id from goal_exchange where entry_id = 'j1'"); K1=$(val b "select id from goal_exchange where entry_id = 'k1'")

echo '--- 回覆'
as b 'OK' "insert into goal_exchange_replies(entry, body, emoji) values ('$J1', '好可愛', '❤️')"
as a 'OK 只有表情' "insert into goal_exchange_replies(entry, emoji) values ('$K1', '🤗')"
as a 'ERROR 空回覆' "insert into goal_exchange_replies(entry, body) values ('$K1', '   ')"
as a 'ERROR 表情不在清單' "insert into goal_exchange_replies(entry, emoji) values ('$K1', '💩')"
as a 'ERROR 太長' "insert into goal_exchange_replies(entry, body) values ('$K1', repeat('a', 501))"
as a 'ERROR 冒名' "insert into goal_exchange_replies(entry, author, body) values ('$K1', '$B', '冒名')"
as e 'ERROR 別隊' "insert into goal_exchange_replies(entry, body) values ('$K1', '闖入')"
as e '0' "select count(*) from goal_exchange_replies"
as a '2' "select count(*) from goal_exchange_replies"
as a 'ERROR 不能改回覆' "update goal_exchange_replies set body = '改'"
as a '（空的，不能刪別人的）' "delete from goal_exchange_replies where author = '$B' returning body"
as b '好可愛' "delete from goal_exchange_replies where author = '$B' returning body"

echo '--- 每日一問：兩人都答了才看得到對方'
as a 'OK' "insert into goal_daily_answers(party_id, day, qid, body) values ('$PA', current_date, 7, '想去京都')"
as a 'ERROR 重複回答' "insert into goal_daily_answers(party_id, day, qid, body) values ('$PA', current_date, 7, '再答一次')"
as a 'ERROR 不能改' "update goal_daily_answers set body = '改答案'"
as a 'ERROR 不能刪' "delete from goal_daily_answers"
as a 'ERROR 太久以前' "insert into goal_daily_answers(party_id, day, qid, body) values ('$PA', current_date - 5, 7, '補答')"
as a 'ERROR 空白' "insert into goal_daily_answers(party_id, day, qid, body) values ('$PA', current_date - 1, 7, '  ')"
as a 'ERROR 冒名' "insert into goal_daily_answers(party_id, user_id, day, qid, body) values ('$PA', '$B', current_date, 7, '冒名')"
as e 'ERROR 別隊' "insert into goal_daily_answers(party_id, day, qid, body) values ('$PA', current_date, 7, '闖入')"
as b '0（還沒答，看不到 Wayne 的）' "select count(*) from goal_daily_answers"
as a '1（只看到自己的）' "select count(*) from goal_daily_answers"
as b 'OK' "insert into goal_daily_answers(party_id, day, qid, body) values ('$PA', current_date, 7, '想去北海道')"
as b '2（都答了）' "select count(*) from goal_daily_answers"
as a '2（都答了）' "select count(*) from goal_daily_answers"
as e '0' "select count(*) from goal_daily_answers"
as a 'OK 昨天' "insert into goal_daily_answers(party_id, day, qid, body) values ('$PA', current_date - 1, 3, '昨天的')"
as b '2（昨天沒答，看不到）' "select count(*) from goal_daily_answers"

echo '--- 離開隊伍：收回自己分享的東西，自己也看不到對方的'
as b 'OK' "insert into goal_exchange_replies(entry, body) values ('$J1', '再回一次')"
as b '小美' "delete from goal_party_members where user_id = '$B' returning display_name"
as a '1 篇（小美的收回了）' "select count(*) from goal_exchange"
as a '0（小美的回覆收回了，Wayne 回自己的那則跟著小美的日記刪了）' "select count(*) from goal_exchange_replies"
as a '2（只剩自己的答案）' "select count(*) from goal_daily_answers"
as b '0' "select count(*) from goal_exchange"
as b '0' "select count(*) from goal_daily_answers where user_id <> '$B'"
as b 'ERROR 已經不在隊上' "insert into goal_exchange(party_id, entry_id, day, body) values ('$PA', 'k9', current_date, '偷塞')"
echo '--- 最後一個人離開：隊伍和交換日記都刪掉'
as a 'Wayne' "delete from goal_party_members where user_id = '$A' returning display_name"
$P -c "select 'exchange 剩 ' || count(*) from goal_exchange"
$P -c "select 'answers 剩 ' || count(*) from goal_daily_answers"
