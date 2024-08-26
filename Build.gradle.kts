/*
 * Licensed Materials - Property of IBM
 *
 * 5900-AJ5
 *
 * (C) Copyright IBM Corp. 2022. All Rights Reserved.
 *
 *  US Government Users Restricted Rights - Use, duplication or disclosure restricted by GSA ADP Schedule Contract with IBM Corp.
 *
 */

import org.gradle.api.tasks.testing.logging.*

plugins {
    id("idea")
    id("eclipse")
    id("base")

    // do not apply these plugins to the root project but define/manage the versions in one place
    id("io.freefair.lombok") apply false
    //id("org.owasp.dependencycheck") apply false
    id("com.diffplug.spotless") apply false

    id("com.ibm.oms.sip.conventions.root-project")

    // Even though the root project is not a java project, we need the java
    // convention because we need it's dependency management for the jacoco
    // report aggregation plugin
    id("com.ibm.oms.sip.conventions.java")

    // Provide aggregation for jacoco reports so sonarqube reports coverage
    // properly
    id("jacoco-report-aggregation")
}

// versions for un-managed dependencies
val openCsvVersion: String by project
val micrometerVersion: String by project
val googleJavaFormatVersion: String by project
val caffeineLibVersion: String by project
val cassandra4DriverVersion: String by project
val openTelemetrySdkVersion: String by project
val awaitilityVersion: String by project

// Get a list of all projects that build java
fun getJvmProjectList(): List<Project> {
    return subprojects.filter({ p ->
        p.name == "app" ||
        p.name == "ratetime-api" ||
        p.name == "config-api" ||
        p.name == "common" ||
        p.name == "db"
    })
}

// Specify top-level report dependencies
dependencies {
    jacocoAggregation(project(":app"))
    jacocoAggregation(project(":db"))
}

reporting {
    reports {
        val unifiedCoverageReport by creating(JacocoCoverageReport::class) {
            testType.set(TestSuiteType.UNIT_TEST)
            rootProject.ext["unifiedReportPath"] = rootProject.layout.buildDirectory.file("reports/jacoco/${this.name}/${this.name}.xml").map { it.asFile.path }
        }
    }
}

// Wire in the aggregated report generation
tasks.check {
    dependsOn("unifiedCoverageReport")
}

// Run the unified coverage report task when the pipeline runs the jacocoTestReport task
tasks.jacocoTestReport {
    finalizedBy("unifiedCoverageReport")
}

// This custom task updates the deployment manifest for CAS. See the ,ci/configure_deployment.sh file
// for more details.
//
// TODO tie this into the pipeline
tasks.register("configureDeployment") {
    group = "deployment"
    description = "Update CAS deployment manifest"

    doLast {
        val appProject : Project = project.findProject(":app")!!
        val appImageName = appProject.extra.properties["dockerImageName"]
        val imageTag = appProject.extra.properties["dockerTagName"]

        val dbProject : Project = project.findProject(":db")!!
        val dbUpdateImageName = dbProject.extra.properties["dockerImageName"]

        val myBuildDir = project.layout.buildDirectory.map { it.asFile.path }.get()

        exec {
            commandLine("mkdir", "-p", myBuildDir)
        }
        exec {
            environment = mapOf(
                "APP_DOCKER_IMAGE_NAME" to appImageName,
                "DB_UPDATE_DOCKER_IMAGE_NAME" to dbUpdateImageName,
                "CICD_ICR_PUBLISH_REPO" to project.findProperty("CICD_ICR_PUBLISH_REPO") as String,
                "BUILD_DIR" to myBuildDir,
                "BUILD_VERSION" to imageTag,
                "GIT_USERNAME" to System.getenv().get("GIT_USERNAME"),
                "GIT_PASSWORD" to System.getenv().get("GIT_PASSWORD")
            )
            commandLine(project.rootDir.path + "/.ci/configure_deployment.sh")
        }
    }
}

configure(getJvmProjectList()) {
    group = project.findProperty("PROJECT_GROUP_ID") as String
    version = project.findProperty("PROJECT_VERSION") as String

    apply(plugin = "com.ibm.oms.sip.conventions.java")

    // Lombok applies to all subprojects
    apply(plugin = "io.freefair.lombok")

    // apply OWASP dependency check to all subprojects and configure in one place
//    apply(plugin = "org.owasp.dependencycheck")
//    configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
//        skipConfigurations.add("annotationProcessor")
//        failBuildOnCVSS = 4f    // fail the build if any unsuppressed medium or higher severity CVE issue found
//        // track false positives and approved CVE suppressions through suppression file
//        suppressionFiles = listOf("${rootDir}/owasp-suppressions.xml")
//        outputDirectory = "${rootDir}/build/security-report"
//    }

    apply(plugin = "com.diffplug.spotless")
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        // optional: limit format enforcement to just the files changed since this point
        //  CAUTION : may not work on CI severs with limited depth checkout
        //ratchetFrom = "origin/main"

        // all formats will be interpreted as UTF-8
        //encoding = java.nio.charset.StandardCharsets.UTF_8

        java {
            // for carefully handcrafted section
            toggleOffOn()
            // adjust the order of import statement using custom grouping
            importOrder("java", "javax", "io.vertx", "io.reactivex", "org.slf4j", "")
            removeUnusedImports()
            // format the source text using google java format plugin using AOSP style (for indentation using 4 spaces)
            //googleJavaFormat(googleJavaFormatVersion).aosp().reflowLongStrings()
            // update copyright text header in source files
            licenseHeaderFile("${rootDir}/license.template")
        }
    }

    val implementation by configurations
    val annotationProcessor by configurations
    val testImplementation by configurations

    dependencies {
        implementation("io.vertx:vertx-rx-java3")
        implementation("io.vertx:vertx-web-client")
        implementation("io.vertx:vertx-web-validation")
        implementation("io.vertx:vertx-kafka-client")
        implementation("io.vertx:vertx-opentelemetry")

        implementation("io.micrometer:micrometer-registry-prometheus:$micrometerVersion")

        implementation("org.apache.logging.log4j:log4j-api")
        implementation("org.apache.logging.log4j:log4j-core")
        implementation("org.apache.logging.log4j:log4j-slf4j2-impl")
        implementation("org.apache.logging.log4j:log4j-layout-template-json")

        // This is to ensure we can use Lombok with Cassandra Mapper entities (annotation processor defined below)
        //  NOTE: the order of these annotation processors is important
        annotationProcessor("org.projectlombok:lombok")

        implementation("com.datastax.oss:java-driver-core")
        implementation("com.datastax.oss:java-driver-query-builder")
        annotationProcessor("com.datastax.oss:java-driver-mapper-processor")
        implementation("com.datastax.oss:java-driver-mapper-runtime")

        implementation("com.datastax.oss:java-driver-metrics-micrometer")

        implementation("org.apache.kafka:kafka-clients")
        implementation("com.opencsv:opencsv:$openCsvVersion")

        implementation("org.apache.commons:commons-lang3")
        implementation("org.apache.commons:commons-collections4")
        implementation("com.github.ben-manes.caffeine:caffeine:$caffeineLibVersion")

        implementation(platform("io.opentelemetry:opentelemetry-bom:$openTelemetrySdkVersion"))
        implementation(platform("io.opentelemetry:opentelemetry-bom-alpha:${openTelemetrySdkVersion}-alpha"))
        implementation("io.opentelemetry:opentelemetry-api")
        implementation("io.opentelemetry:opentelemetry-exporter-otlp")
        implementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")

        testImplementation("org.junit.jupiter:junit-jupiter")
        testImplementation("io.vertx:vertx-junit5")
        testImplementation("io.vertx:vertx-junit5-rx-java3")

        testImplementation("io.rest-assured:rest-assured")
        testImplementation("org.assertj:assertj-core")
        testImplementation("org.mockito:mockito-core")
        testImplementation("org.mockito:mockito-inline")
        testImplementation("org.awaitility:awaitility:${awaitilityVersion}")
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        System.getProperties().forEach { k: Any, v: Any ->
            val propKey: String = k.toString();
            if (propKey.startsWith("cas.test.")) {
                systemProperties[propKey.substring("cas.test.".length)] = v.toString()
            }
        }
        systemProperties["file.encoding"] = "UTF-8"
        systemProperties["datastax-java-driver.basic.request.timeout"] = "15 seconds"

        environment("TESTCONTAINERS_IMAGES_REGISTRY_OVERRIDE", "docker-na-public.artifactory.swg-devops.com")
        environment("TESTCONTAINERS_IMAGES_REPO_PREFIX", "wce-cicd-docker-io-docker-remote")
        environment("TESTCONTAINERS_IMAGE_SUBSTITUTOR", "com.ibm.sterling.fulfilment.cas.CommonImageNameSubstitutor");
        //maxParallelForks = 1
        //setForkEvery(0)
        jvmArgs("-Xmx1024M")
    }

    // Disable module-based jacoco reporting since we are using the aggregation plugin
    tasks.jacocoTestReport {
        enabled = false
    }

    // Make sure sonarqube uses the aggregated test report when analyzing a module
    sonarqube {
        properties {
            property("sonar.coverage.jacoco.xmlReportPaths", rootProject.ext.get("unifiedReportPath")!! )
        }
    }
}


