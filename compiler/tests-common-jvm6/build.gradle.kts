
apply { plugin("kotlin") }
apply { plugin("jps-compatible") }

jvmTarget = "1.6"

dependencies {
    compile(project(":kotlin-stdlib"))
    compile(project(":kotlin-test:kotlin-test-jvm"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { }
}