plugins {
    id("inmc.paper-plugin")
}

group = "com.inmc.dungeon"
version = "1.0.0"

inmc {
    paper = "26.2"
    pluginName = "inmc-dungeon"
}

// WorldEdit API — 테섭의 FastAsyncWorldEdit jar 에서 API 패키지만 꺼내 **컴파일에만** 쓴다(새로 받지 않는다 — 타이틀포지의 packetevents 와 같은 방식).
// 판을 올리면 파일 이름도 같이 고친다. 쓰는 곳은 world/Schematics 하나.
val worldEditApi = tasks.register<Sync>("worldEditApi") {
    from(zipTree(rootProject.file("server/plugins/FastAsyncWorldEdit-Paper-2.15.4.jar"))) {
        include("com/sk89q/**", "com/fastasyncworldedit/**", "org/enginehub/**")
    }
    into(layout.buildDirectory.dir("worldedit-api"))
}

dependencies {
    compileOnly(files(worldEditApi))
    // 몬스터의 MonsterAPI·사건을 그대로 쓴다(던전용으로 만들어 둔 API). 런타임에는 paper-plugin.yml 의 join-classpath.
    compileOnly(project(":monster"))
    testImplementation(project(":monster"))
}
