#!/bin/bash
# 在本機 PostgreSQL 模擬 Supabase，實測 sync/party-v5.sql（一起看的片、想一起看的片單）的權限。
# 用法（root 或 postgres 使用者）：bash sync/test/run-party-v5-test.sh
set -e
DIR=$(cd "$(dirname "$0")/.." && pwd); PG=/usr/lib/postgresql/16/bin; T=/tmp/goal-pgtest
if [ ! -d $T/data ]; then
  mkdir -p $T && chown postgres $T
  su postgres -s /bin/sh -c "$PG/initdb -D $T/data -U postgres -A trust >/dev/null"
fi
if ! su postgres -s /bin/sh -c "$PG/pg_ctl -D $T/data status" >/dev/null 2>&1; then
  su postgres -s /bin/sh -c "$PG/pg_ctl -D $T/data -o '-k $T -p 54329 -c listen_addresses=' -l $T/log start >/dev/null"; sleep 2
fi
P="psql -h $T -p 54329 -U postgres -q"
$P -c 'drop database if exists goal_test5' -c 'create database goal_test5' >/dev/null
for r in anon authenticated; do $P -c "do \$\$ begin create role $r nologin; exception when duplicate_object then null; end \$\$" >/dev/null; done
grep -v '^create role' "$DIR/test/mock-supabase.sql" | $P -d goal_test5 -v ON_ERROR_STOP=1 >/dev/null
for f in supabase-setup party-setup party-v2 party-v3 party-v4 party-v5 party-v5; do $P -d goal_test5 -v ON_ERROR_STOP=1 -f "$DIR/$f.sql" 2>/dev/null; done
echo '可以重複執行：OK'
P="psql -h $T -p 54329 -U postgres -d goal_test5 -qtA"
as() { local u=$1 want=$2; shift 2; local sub=""; [ "$u" != "-" ] && sub="00000000-0000-0000-0000-00000000000$u"; local role=authenticated; [ "$u" = "-" ] && role=anon
  printf '%-2s [應該 %s] %s\n     => ' "$u" "$want" "$*"; $P -c "set role $role; set request.jwt.claim.sub = '$sub'; $*" 2>&1 | sed 's/^psql:.*ERROR:  /ERROR: /;s/CONTEXT:.*//' | tr '\n' ' '; echo; }
val() { local u=$1; shift; $P -c "set role authenticated; set request.jwt.claim.sub='00000000-0000-0000-0000-00000000000$u'; $*" | tail -1; }
A=00000000-0000-0000-0000-00000000000a; B=00000000-0000-0000-0000-00000000000b; C=00000000-0000-0000-0000-00000000000c

echo '--- 準備：A、B 是情侶隊；C、D 是另一隊；E 沒有隊伍'
CODE=$(val a "select invite_code from public.goal_create_party_kind('我們', 'Wayne', 'couple')")
val b "select public.goal_join_party('$CODE', '小美')" >/dev/null
OLD=$(val c "select invite_code from public.goal_create_party('別隊', 'C')")
val d "select public.goal_join_party('$OLD', 'D')" >/dev/null
PA=$(val a "select public.goal_my_party()"); PC=$(val c "select public.goal_my_party()")
COLS="party_id, media, tmdb, season, title, year, poster, rating, review, day"

echo '--- 一起看的片：寫了'
as a 'OK' "insert into goal_watch_together($COLS) values ('$PA', 'movie', 603, 0, '駭客任務', 1999, '/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg', 9, 'Wayne 的心得', current_date)"
as a 'ERROR 重複' "insert into goal_watch_together(party_id, media, tmdb, title, day) values ('$PA', 'movie', 603, '再一次', current_date)"
as a 'ERROR 冒名' "insert into goal_watch_together(party_id, user_id, media, tmdb, title, day) values ('$PA', '$B', 'movie', 1, '冒名', current_date)"
as a 'ERROR 別隊' "insert into goal_watch_together(party_id, media, tmdb, title, day) values ('$PC', 'movie', 2, '別隊', current_date)"
as e 'ERROR 沒隊伍' "insert into goal_watch_together(party_id, media, tmdb, title, day) values ('$PA', 'movie', 3, '闖入', current_date)"
as a 'ERROR 海報格式' "insert into goal_watch_together(party_id, media, tmdb, title, poster, day) values ('$PA', 'movie', 4, 'x', 'javascript:alert(1)', current_date)"
as a 'ERROR 海報網址' "insert into goal_watch_together(party_id, media, tmdb, title, poster, day) values ('$PA', 'movie', 4, 'x', '//evil.example/a.jpg', current_date)"
as a 'ERROR 分數' "insert into goal_watch_together(party_id, media, tmdb, title, rating, day) values ('$PA', 'movie', 4, 'x', 11, current_date)"
as a 'ERROR 類型' "insert into goal_watch_together(party_id, media, tmdb, title, day) values ('$PA', 'person', 4, 'x', current_date)"
as a 'ERROR 心得太長' "insert into goal_watch_together(party_id, media, tmdb, title, review, day) values ('$PA', 'movie', 4, 'x', repeat('字', 3001), current_date)"
as a 'ERROR 空片名' "insert into goal_watch_together(party_id, media, tmdb, title, day) values ('$PA', 'movie', 4, '', current_date)"
as a 'ERROR 未來' "insert into goal_watch_together(party_id, media, tmdb, title, day) values ('$PA', 'movie', 4, 'x', current_date + 5)"
as a 'ERROR 季數' "insert into goal_watch_together(party_id, media, tmdb, season, title, day) values ('$PA', 'tv', 4, 999, 'x', current_date)"

echo '--- 還沒寫的人只看得到片名（沒有分數、心得）'
as b '0（看不到 Wayne 的心得）' "select count(*) from goal_watch_together"
as b '駭客任務（只有片名）' "select title from public.goal_watch_titles()"
as b 'ERROR 函式沒有分數欄位' "select rating from public.goal_watch_titles()"
as c '0（別隊）' "select count(*) from public.goal_watch_titles()"
as c '0' "select count(*) from goal_watch_together"
as e '0' "select count(*) from public.goal_watch_titles()"
as - 'ERROR' "select count(*) from public.goal_watch_titles()"
as - 'ERROR' "select count(*) from goal_watch_together"
as b 'ERROR 不能直接呼叫' "select public.goal_watch_leave()"

echo '--- 兩個人都寫了才揭曉'
as b 'OK' "insert into goal_watch_together($COLS) values ('$PA', 'movie', 603, 0, '駭客任務', 1999, null, 7, '小美的心得', current_date)"
as b '2（看到 Wayne 的 9 分）' "select count(*) || ' ' || string_agg(rating::text, ',' order by rating) from goal_watch_together"
as a '2' "select count(*) from goal_watch_together"
as c '0' "select count(*) from goal_watch_together"
echo '--- 影集按季分開'
as b 'OK 第 1 季' "insert into goal_watch_together($COLS) values ('$PA', 'tv', 1396, 1, '絕命毒師', 2008, null, 10, 'S1', current_date)"
as a 'OK 第 2 季' "insert into goal_watch_together($COLS) values ('$PA', 'tv', 1396, 2, '絕命毒師', 2008, null, 8, 'S2', current_date)"
as a '3（自己的電影和第 2 季、小美的電影；看不到小美的第 1 季）' "select count(*) from goal_watch_together"
as a '4 列片名' "select count(*) from public.goal_watch_titles()"
as a 'OK 第 1 季' "insert into goal_watch_together($COLS) values ('$PA', 'tv', 1396, 1, '絕命毒師', 2008, null, 9, 'S1 Wayne', current_date)"
as a '5（第 1 季揭曉了）' "select count(*) from goal_watch_together"
as b '4（看不到 Wayne 的第 2 季）' "select count(*) from goal_watch_together"

echo '--- 修改、收回'
as a 'upsert（跟 PostgREST 一樣更新全部欄位）' "insert into goal_watch_together($COLS) values ('$PA', 'movie', 603, 0, '駭客任務', 1999, null, 10, '改成 10 分', current_date) on conflict (user_id, media, tmdb, season) do update set party_id = excluded.party_id, media = excluded.media, tmdb = excluded.tmdb, season = excluded.season, title = excluded.title, year = excluded.year, poster = excluded.poster, rating = excluded.rating, review = excluded.review, day = excluded.day returning rating"
as b 'ERROR 不能用 upsert 蓋掉別人的' "insert into goal_watch_together(party_id, user_id, media, tmdb, title, day) values ('$PA', '$A', 'movie', 603, 'hack', current_date) on conflict (user_id, media, tmdb, season) do update set review = excluded.review"
as b '（空的，不能改別人的）' "update goal_watch_together set review = '亂改' where user_id = '$A' returning review"
as a 'ERROR 不能換隊' "update goal_watch_together set party_id = '$PC' where tmdb = 603"
as a 'ERROR 不能換人' "update goal_watch_together set user_id = '$B' where tmdb = 603"
as a 'ERROR 不能改時間' "update goal_watch_together set created_at = now() - interval '1 year' where tmdb = 603"
as b '（空的，不能刪別人的）' "delete from goal_watch_together where user_id = '$A' returning title"
as a '絕命毒師 2' "delete from goal_watch_together where tmdb = 1396 and season = 2 returning title || ' ' || season"

echo '--- 片單'
as a 'OK' "insert into goal_watchlist(party_id, media, tmdb, title, year, poster) values ('$PA', 'movie', 27205, '全面啟動', 2010, '/9gk7adHYeDvHkCSEqAvQNLV5Uge.jpg')"
as b 'ERROR 重複' "insert into goal_watchlist(party_id, media, tmdb, title) values ('$PA', 'movie', 27205, '全面啟動')"
as b 'OK 影集' "insert into goal_watchlist(party_id, media, tmdb, title) values ('$PA', 'tv', 1399, '冰與火之歌')"
as a 'ERROR 冒名' "insert into goal_watchlist(party_id, added_by, media, tmdb, title) values ('$PA', '$B', 'movie', 5, '冒名')"
as a 'ERROR 別隊' "insert into goal_watchlist(party_id, media, tmdb, title) values ('$PC', 'movie', 5, '別隊')"
as e 'ERROR 沒隊伍' "insert into goal_watchlist(party_id, media, tmdb, title) values ('$PA', 'movie', 5, '闖入')"
as a 'ERROR 海報格式' "insert into goal_watchlist(party_id, media, tmdb, title, poster) values ('$PA', 'movie', 5, 'x', '\"><img src=x>')"
as c 'OK 別隊自己的片單' "insert into goal_watchlist(party_id, media, tmdb, title) values ('$PC', 'movie', 27205, '全面啟動')"
as a '2' "select count(*) from goal_watchlist"
as b '2' "select count(*) from goal_watchlist"
as c '1' "select count(*) from goal_watchlist"
as e '0' "select count(*) from goal_watchlist"
as - 'ERROR' "select count(*) from goal_watchlist"
as a 'ERROR 不能改' "update goal_watchlist set title = '改'"
as c '（空的，不能刪別隊的）' "delete from goal_watchlist where party_id = '$PA' returning title"
as b '全面啟動（同隊都可以刪）' "delete from goal_watchlist where tmdb = 27205 and party_id = '$PA' returning title"

echo '--- 離開隊伍：收回自己一起看的心得；片單留給還在的人'
as b '小美' "delete from goal_party_members where user_id = '$B' returning display_name"
as a '2（只剩自己的）' "select count(*) from goal_watch_together"
as a '2' "select count(*) from public.goal_watch_titles()"
as a '1（小美加的片還在）' "select count(*) from goal_watchlist"
as b '0' "select count(*) from goal_watch_together where user_id <> '$B'"
as b '0' "select count(*) from goal_watchlist"
as b 'ERROR 已經不在隊上' "insert into goal_watch_together(party_id, media, tmdb, title, day) values ('$PA', 'movie', 9, '偷塞', current_date)"
echo '--- 最後一個人離開：隊伍、心得、片單都刪掉'
as a 'Wayne' "delete from goal_party_members where user_id = '$A' returning display_name"
$P -c "select 'watch_together 剩 ' || count(*) from goal_watch_together"
$P -c "select 'watchlist 剩 ' || count(*) || '（別隊的 1 部）' from goal_watchlist"
