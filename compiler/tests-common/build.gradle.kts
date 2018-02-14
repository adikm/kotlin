
apply { plugin("kotlin") }
apply { plugin("jps-compatible") }

dependencies {
    compile(project(":core:descriptors"))
    compile(project(":core:descriptors.jvm"))
    compile(project(":core:deserialization"))
    compile(project(":compiler:util"))
    compile(project(":compiler:backend"))
    compile(project(":compiler:ir.ir2cfg"))
    compile(project(":compiler:frontend"))
    compile(project(":compiler:frontend.java"))
    compile(project(":compiler:util"))
    compile(project(":compiler:cli-common"))
    compile(project(":compiler:cli"))
    compile(project(":compiler:light-classes"))
    compile(project(":compiler:serialization"))
    compile(project(":kotlin-preloader"))
    compile(project(":compiler:daemon-common"))
    compile(project(":js:js.serializer"))
    compile(project(":js:js.frontend"))
    compile(project(":js:js.translator"))
    compileOnly(project(":plugins:android-extensions-compiler"))
    compile(project(":kotlin-test:kotlin-test-jvm"))
    compile(project(":compiler:tests-common-jvm6"))
    compileOnly(project(":kotlin-reflect-api"))
    compile(commonDep("junit:junit"))
    compile(androidDxJar()) { isTransitive = false }
    compile(intellijCoreDep()) { includeJars("intellij-core"); isTransitive = false }
    compile(intellijDep()) {
        includeJars("openapi", "idea", "idea_rt", "guava", "trove4j", "picocontainer", "asm-all", "log4j", "jdom", rootProject = rootProject)
        isTransitive = false
    }
}

sourceSets {
    "main" { projectDefault() }
    "test" { }
}