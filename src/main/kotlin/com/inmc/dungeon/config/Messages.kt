package com.inmc.dungeon.config

import com.inmc.dungeon.util.Ph
import kr.inmc.core.config.MessageCatalog
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `messages.yml` 한 벌. 읽고 보내는 부분은 core 의 [MessageCatalog] 가 갖고 있고 여기는 기본값 표뿐이다.
 * `ResourceTest` 가 배포 파일과 이 표의 키가 정확히 같은지, 코드가 부르는 키가 전부 있는지 지킨다.
 */
class Messages(values: Map<String, String>) : MessageCatalog<Ph>(values, DEFAULTS) {

    companion object {

        fun from(config: YamlConfiguration): Messages = Messages(merge(DEFAULTS, config))

        val DEFAULTS: Map<String, String> = linkedMapOf(
            PREFIX to "<gradient:#8e2de2:#4a00e0>[ 던전 ]</gradient> ",

            // --- 공통 ---------------------------------------------------------------
            "player-only" to "<red>플레이어만 쓸 수 있습니다.</red>",
            "no-permission" to "<red>권한이 없습니다.</red>",
            "reloaded" to "<green>다시 불러왔습니다. 던전 {개수}개 · 방 {수량}개.</green>",
            "unknown-dungeon" to "<red>던전 '{아이디}' 을(를) 모릅니다.</red>",
            "unknown-difficulty" to "<red>난이도 '{아이디}' 을(를) 모릅니다.</red>",
            "unknown-room" to "<red>방 '{아이디}' 을(를) 모릅니다.</red>",
            "dungeon-disabled" to "<red>꺼져 있는 던전입니다.</red>",

            // --- 시작 ---------------------------------------------------------------
            "world-not-ready" to "<red>던전 월드가 준비되지 않았습니다(서버 로그를 보세요).</red>",
            "already-in-run" to "<red>이미 던전에 들어가 있는 사람이 있습니다.</red>",
            "no-start-room" to "<red>이 던전에는 시작의 방이 없습니다 — 같은 테마로 시작의 방을 저장하세요.</red>",
            "full" to "<yellow>지금 열린 던전이 가득 찼습니다({개수}). 잠시 뒤에 다시 시도하세요.</yellow>",
            "preparing" to "<gray>{던전} <dark_gray>·</dark_gray> {난이도} <gray>— 던전을 여는 중…</gray>",
            "not-in-run" to "<red>던전 안에 있지 않습니다.</red>",
            "left" to "<gray>던전에서 나왔습니다.</gray>",
            "use-leave" to "<red>던전 안에서는 쓸 수 없습니다. 나가려면 </red><white>/던전 나가기</white>",
            "blocked" to "<red>던전 안에서는 할 수 없습니다.</red>",

            // --- 진행 ---------------------------------------------------------------
            "room-fight" to "<red>몬스터 {개수}마리!</red> <gray>모두 쓰러뜨려야 문이 열립니다.</gray>",
            "boss-appeared" to "<dark_red><bold>보스가 나타났습니다!</bold></dark_red>",
            "room-cleared" to "<green>방을 정리했습니다.</green> <gold>+{개수} 금화</gold> <gray>(가진 금화 {수량})</gray>",
            "wave-next" to "<red>다음 웨이브!</red> <gray>({개수}/{수량})</gray>",
            "monster-missing" to "<yellow>몬스터를 부르지 못해 이 방은 그냥 지나갑니다(관리자에게 알려 주세요).</yellow>",
            "choose" to "<aqua>다음 방을 고르세요</aqua> <gray>— 출구 위 {개수}개 중 하나를 클릭({값}초)</gray>",
            "choose-one" to "<aqua>갈 수 있는 방이 하나뿐입니다</aqua> <gray>— 출구 위 홀로그램을 클릭하세요</gray>",
            "voted" to "<gray>{플레이어} → {방}</gray>",
            "vote-not-allowed" to "<red>지금은 투표할 수 없습니다.</red>",
            "decided" to "<aqua>다음 방: {방}</aqua> <gray>— 문을 여는 중…</gray>",
            "door-open" to "<green>문이 열렸습니다!</green> <gray>출구로 들어가면 파티가 함께 옮겨 갑니다.</gray>",
            "timer-started" to "<yellow>제한시간이 흐르기 시작했습니다.</yellow>",
            "timer-forced" to "<yellow>시작의 방에서 너무 오래 기다려 제한시간이 흐르기 시작했습니다.</yellow>",

            // --- 비밀방 -------------------------------------------------------------------
            "secret-found" to "<dark_aqua>{플레이어}님이 숨은 출구를 찾았습니다!</dark_aqua> <gray>방을 정리하면 비밀 출구가 선택지에 더해집니다.</gray>",
            "secret-found-choice" to "<dark_aqua>{플레이어}님이 숨은 출구를 찾았습니다!</dark_aqua> <gray>선택지가 하나 늘었습니다 — 투표 시간 +{값}초.</gray>",
            "secret-late" to "<gray>숨은 출구가 있지만 이미 다음 방이 정해졌습니다.</gray>",
            "secret-hidden" to "<gray>출구 하나가 <bold>???</bold> 로 가려져 있습니다 — 무엇이 있을지 모릅니다.</gray>",

            // --- 방 효과 · 보상 ---------------------------------------------------------
            "treasure-chest" to "<gold>보상 상자가 있습니다</gold> <gray>— 눌러서 열면 내 몫을 받습니다(사람마다 한 번).</gray>",
            "boss-chest" to "<gold><bold>보스 보상 상자!</bold></gold> <gray>눌러서 내 몫을 받으세요 — {값}초 안에 안 열면 나갈 때 저절로 받습니다.</gray>",
            "chest-already" to "<gray>이미 이 상자에서 내 몫을 받았습니다.</gray>",
            "chest-empty" to "<gray>상자가 비어 있습니다(보상 상자가 설정되지 않음).</gray>",
            "chest-auto" to "<gray>열지 않은 보상 상자의 내 몫을 받았습니다.</gray>",
            "gear-got" to "<light_purple>던전 장비</light_purple> <white>{값}</white><gray> 을(를) 얻었습니다!</gray>",
            "rest" to "<green>회복방 — 모두의 체력과 배고픔이 회복되고 해로운 효과가 사라졌습니다.</green>",
            "cursed" to "<dark_purple>저주가 내렸습니다: {값}</dark_purple> <gray>— 대신 이번 판의 보상 +{수량}</gray>",
            "blessing-done" to "<gray>이 방에서 이미 축복을 받았습니다.</gray>",
            "blessing-none" to "<gray>받을 수 있는 축복이 없습니다(던전 정의의 blessings).</gray>",
            "blessed" to "<aqua>축복을 받았습니다: {값}</aqua>",
            "altar-used" to "<gray>이 제단은 이미 썼습니다.</gray>",
            "altar-nothing" to "<gray>제단이 아무 반응도 없습니다.</gray>",
            "altar-good" to "<aqua>제단이 축복을 내렸습니다: {값}</aqua>",
            "altar-bad" to "<dark_purple>제단이 저주를 내렸습니다: {값}</dark_purple>",
            "shop-poor" to "<red>금화가 모자랍니다 — 가격 {개수} · 가진 금화 {수량}</red>",
            "shop-bought" to "<green>샀습니다.</green> <gray>-{개수} 금화 (남은 금화 {수량})</gray>",
            "item-outside" to "<gray>던전 안에서만 쓸 수 있습니다.</gray>",
            "item-no-downed" to "<gray>쓰러진 파티원이 없습니다.</gray>",
            "item-revive-ticket" to "<yellow>{플레이어} 님이 부활권을 썼습니다</yellow> <gray>— 남은 부활 {개수}</gray>",
            "item-party-heal" to "<green>{플레이어} 님이 파티를 회복시켰습니다.</green>",
            "item-no-curse" to "<gray>지울 저주가 없습니다.</gray>",
            "item-cleansed" to "<aqua>저주가 모두 사라졌습니다.</aqua>",
            "exit-soon" to "<gray>모두 보상을 받았습니다 — {개수}초 뒤 밖으로 나갑니다.</gray>",
            "admin-gold" to "<green>판 금화 +{개수} (가진 금화 {수량})</green>",
            "admin-buff" to "<green>{값} 을(를) 붙였습니다.</green>",
            "admin-buff-unknown" to "<red>buffs.yml 에 '{아이디}' 이(가) 없습니다.</red>",
            "admin-item" to "<green>던전 전용 {값} 을(를) 받았습니다.</green>",
            "admin-item-unknown" to "<red>만들 수 없는 아이템입니다: {아이디}</red>",

            // --- 쓰러짐 -------------------------------------------------------------
            "downed" to "<red>{플레이어} 님이 쓰러졌습니다.</red> <gray>남은 부활 {개수}</gray>",
            "downed-final" to "<dark_red>{플레이어} 님이 쓰러졌습니다 — 남은 부활이 없습니다.</dark_red>",
            "revived" to "<green>{플레이어} 님이 부활했습니다.</green>",

            // --- 파티 ---------------------------------------------------------------
            "member-left" to "<gray>{플레이어} 님이 던전에서 나갔습니다.</gray>",
            "member-offline" to "<gray>{플레이어} 님의 접속이 끊겼습니다 — {값}초 기다립니다.</gray>",
            "leader-passed" to "<yellow>파티장이 {플레이어} 님이 되었습니다.</yellow>",
            "reconnected" to "<green>던전으로 돌아왔습니다.</green>",

            // --- 포탈 ---------------------------------------------------------------
            "portal-found" to "<light_purple>던전 포탈을 발견했습니다!</light_purple> <gray>{던전} {난이도}<gray> — 입구로 들어가면 입장 화면이 열립니다({값}초 동안).</gray>",
            "portal-owner-only" to "<gray>{플레이어} 님이 발견한 포탈입니다 — {값}초 뒤 누구나 들어갈 수 있습니다.</gray>",
            "difficulty-locked" to "<red>잠긴 난이도입니다 — 바로 앞 난이도 {난이도}<red> 을(를) 먼저 깨세요.</red>",

            // --- 파티 ---------------------------------------------------------------
            "party-none" to "<gray>파티가 없습니다 — 던전 포탈에 들어가 파티를 만드세요.</gray>",
            "party-not-leader" to "<red>파티장만 할 수 있습니다.</red>",
            "party-already-member" to "<yellow>이미 파티에 있습니다.</yellow>",
            "party-full" to "<red>파티가 가득 찼습니다.</red>",
            "party-target-busy" to "<red>던전 안에 있는 사람은 초대할 수 없습니다.</red>",
            "party-target-offline" to "<red>접속 중인 플레이어가 아닙니다.</red>",
            "party-invited" to "<aqua>{플레이어} 님이 {던전} {난이도}<aqua> 파티에 초대했습니다.</aqua> <click:run_command:'/던전 파티 수락 {아이디}'><green><bold>[수락]</bold></green></click> <click:run_command:'/던전 파티 거절 {아이디}'><red>[거절]</red></click> <gray>({값}초)</gray>",
            "party-invite-sent" to "<gray>{플레이어} 님을 초대했습니다.</gray>",
            "party-declined" to "<gray>{플레이어} 님이 초대를 거절했습니다.</gray>",
            "party-gone" to "<red>그 파티는 이제 없습니다.</red>",
            "party-no-invite" to "<red>받은 초대가 없거나 시간이 지났습니다.</red>",
            "party-joined" to "<green>{플레이어} 님이 파티에 들어왔습니다.</green> <gray>({개수}/{수량})</gray>",
            "party-left-self" to "<gray>파티에서 나왔습니다.</gray>",
            "party-leader-now" to "<yellow>파티장이 {플레이어} 님이 되었습니다.</yellow>",
            "party-member-left" to "<gray>{플레이어} 님이 파티에서 나갔습니다.</gray>",
            "party-kicked" to "<red>파티에서 내보내졌습니다.</red>",
            "party-not-member" to "<red>파티원이 아닙니다.</red>",
            "party-recruit" to "<light_purple>[던전 파티 모집]</light_purple> <white>{플레이어}</white><gray> 님이 </gray>{던전} {난이도}<gray> 파티원을 모집합니다 ({개수}/{수량})</gray> <click:run_command:'/던전 파티 참여 {아이디}'><green><bold>[참여하기]</bold></green></click>",
            "party-recruited" to "<green>모집 공지를 띄웠습니다.</green>",
            "party-recruit-cooldown" to "<red>모집 공지는 {값}초에 한 번입니다.</red>",
            "party-too-few" to "<red>{개수}명 이상 모여야 입장할 수 있습니다.</red>",
            "party-queued" to "<yellow>열린 던전이 가득 찼습니다({개수}/{수량}) — 자리가 나면 저절로 들어갑니다.</yellow>",
            "party-queue-ready" to "<green>자리가 났습니다 — 던전으로 들어갑니다!</green>",
            "party-timeout" to "<gray>{던전}<gray> 파티가 시간 안에 입장하지 않아 해체됐습니다.</gray>",

            // --- 끝 -----------------------------------------------------------------
            "cleared" to "<gold>{던전} {난이도} 클리어!</gold> <gray>걸린 시간 {수량} — {개수}초 뒤 밖으로 나갑니다.</gray>",
            "rank-new-best" to "<aqua><bold>새 최고 기록!</bold></aqua> <white>{수량}</white> <gray>— {난이도} 최단 순위 {개수}위</gray>",
            "rank-recorded" to "<gray>기록 {수량} · 내 최고 {값} · {난이도} 최단 순위 {개수}위</gray>",
            "rank-assisted" to "<gray>관리자 시험 도구를 쓴 판이라 최단 클리어 순위에 올리지 않습니다.</gray>",

            // --- 순위 ---------------------------------------------------------------------
            "rank-header" to "<gold>━━ {던전} {난이도} 최단 클리어 ━━</gold>",
            "rank-line" to "<yellow>{개수}.</yellow> <white>{플레이어}</white> <gray>— </gray><aqua>{수량}</aqua> <dark_gray>(클리어 {값}번)</dark_gray>",
            "rank-empty" to "<gray>아직 기록이 없습니다.</gray>",
            "rank-mine" to "<gray>내 순위 <yellow>{개수}위</yellow> · 최고 <aqua>{수량}</aqua></gray>",
            "clear-subtitle" to "<gray>걸린 시간 {수량}</gray>",
            "failed" to "<red>던전 실패 — {값}</red> <gray>{개수}초 뒤 밖으로 나갑니다.</gray>",
            "fail-timeout" to "<red>제한시간이 끝났습니다</red>",
            "fail-wiped" to "<red>파티가 모두 쓰러졌습니다</red>",
            "fail-abandoned" to "<red>모두 나갔습니다</red>",
            "fail-build-failed" to "<red>방을 붙이지 못했습니다(관리자에게 알려 주세요)</red>",
            "fail-admin" to "<red>관리자가 끝냈습니다</red>",

            // --- 관리 ---------------------------------------------------------------
            "admin-started" to "<green>판 #{아이디} 을(를) 열었습니다 — {던전} {난이도} · 시드 {값}</green>",
            "admin-ended" to "<yellow>판 #{아이디} 을(를) 끝냈습니다.</yellow>",
            "admin-no-runs" to "<gray>열린 판이 없습니다.</gray>",
            "admin-run-line" to "<gray>#{아이디}</gray> <white>{던전}</white> {난이도} <gray>· {플레이어} · {방} · {값}</gray>",
            "admin-run-header" to "<gold>열린 판 {개수}/{수량}</gold>",
            "admin-cleared" to "<green>몬스터 {개수}마리를 치웠습니다.</green>",
            "admin-next" to "<green>다음 방을 {방} (으)로 정했습니다.</green>",
            "admin-next-busy" to "<red>지금은 다음 방을 바꿀 수 없습니다(방 안이거나 투표 중일 때만).</red>",
            "admin-door-closed" to "<red>열린 문이 없습니다.</red>",
            "admin-secret-found" to "<green>숨은 출구를 찾은 것으로 했습니다.</green>",
            "admin-secret-hidden" to "<green>다음 선택지 하나를 ??? 로 냅니다</green> <gray>(투표 중이면 선택지를 다시 굴립니다).</gray>",
            "admin-rank-reset" to "<green>{아이디} 의 최단 클리어 순위표 {개수}개를 지웠습니다.</green>",
            "admin-secret-fail" to "<red>비밀방을 낼 수 없습니다</red> <gray>— 이 던전 풀에 비밀방이 없거나, 이미 찾았거나, 다음 방이 정해졌습니다.</gray>",
            "admin-time" to "<green>남은 시간을 {값}초 바꿨습니다.</green>",
            "admin-revives" to "<green>남은 부활: {개수}</green>",
            "room-saved" to "<green>방 '{아이디}' 을(를) 저장했습니다 — {방} · 크기 {값} · 도착 {수량} · 출구 {개수}</green>",
            "room-save-problem" to "<yellow> ! {값}</yellow>",
            "room-save-failed" to "<red>방을 저장하지 못했습니다 — {값}</red>",
            "room-no-selection" to "<red>WorldEdit 으로 영역을 고르거나(//wand) 좌표 둘을 적으세요.</red>",
            "room-bad-id" to "<red>방 id 는 영문 소문자·숫자·_·- 만(1~32자) 씁니다.</red>",
            "room-too-big" to "<red>방이 너무 큽니다({값}) — config.yml 의 slot-spacing({개수})보다 작아야 합니다.</red>",
            "room-deleted" to "<yellow>방 '{아이디}' 을(를) 지웠습니다.</yellow>",
            "room-list-header" to "<gold>방 {개수}개</gold>",
            "room-list-line" to "<gray>-</gray> <white>{아이디}</white> {방} <gray>· 테마 {값} · {수량}</gray>",
            "preview-started" to "<green>방 '{아이디}' 을(를) 시험 칸에 붙였습니다.</green> <gray>표시는 떠 있는 글자로 보입니다. 끝내려면 </gray><white>/던전 관리 방 시험끝</white>",
            "preview-ended" to "<gray>방 시험을 끝냈습니다.</gray>",
            "preview-none" to "<gray>시험 중인 방이 없습니다.</gray>",
            "admin-portal" to "<green>포탈 #{아이디} 을(를) 세웠습니다 — {던전} {난이도}</green>",
            "portal-saved" to "<green>포탈 모양 '{아이디}' 을(를) 저장했습니다 — 블록 {개수}개. 던전 정의의 portal 에 이 id 를 적으세요.</green>",
            "portal-save-failed" to "<red>포탈 모양을 저장하지 못했습니다 — {값}</red>",
            "admin-unlock" to "<green>{플레이어} 님의 {던전}<green> 해금 — {값}</green>",

            // --- 검증 ---------------------------------------------------------------
            "verify-header" to "<gold>던전 검증</gold> <gray>— 문제 <red>{개수}</red> · 주의 <yellow>{수량}</yellow></gray>",
            "verify-error" to "<red> ✘ {값}</red>",
            "verify-warn" to "<yellow> ! {값}</yellow>",
            "verify-ok" to "<green> ✔ 문제 없음 — 던전 {개수}개 · 방 {수량}개</green>",

            // --- 도움말 -------------------------------------------------------------
            "help" to listOf(
                "<gold>/던전</gold> <gray>- 내 파티 화면</gray> <dark_gray>·</dark_gray> <gold>/던전 파티 초대·모집·입장·나가기·추방</gold>",
                "<gold>/던전 나가기</gold> <gray>- 던전에서 나오기</gray>",
                "<gold>/던전 순위 [던전] [난이도]</gold> <gray>- 최단 클리어 순위</gray>",
                "<red>/던전 관리 시작 <던전> <난이도> [시드]</red> <gray>- 바로 시작(시험)</gray>",
                "<red>/던전 관리 목록 · 끝내기 [판] · 클리어 · 다음 <방> · 투표 <번호> · 문 · 비밀 [찾기|숨김] · 시간 <초> · 부활 <수></red>",
                "<red>/던전 관리 금화 <수> · 버프 <id> · 아이템 <참조> [수]</red> <gray>- 판 안에서 시험</gray>",
                "<red>/던전 관리 방 저장 <id> <종류> [월드 좌표1 좌표2] · 방 목록 · 방 삭제 <id> · 방 시험 <id> · 방 시험끝</red>",
                "<red>/던전 관리 포탈 <던전> [난이도] · 포탈 저장 <id> · 입장화면 <던전> · 해금 <플레이어> <던전> <난이도|초기화></red>",
                "<red>/던전 관리 순위 초기화 <던전> [난이도] · 리로드 · 검증</red>",
            ).joinToString("\n"),
        )
    }
}
