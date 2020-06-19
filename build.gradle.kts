/*
 * Copyright 2018 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import java.time.Instant

///////////////////////////////
//////    PUBLISH TO SONATYPE / MAVEN CENTRAL
////// TESTING : (to local maven repo) <'publish and release' - 'publishToMavenLocal'>
////// RELEASE : (to sonatype/maven central), <'publish and release' - 'publishToSonatypeAndRelease'>
///////////////////////////////

plugins {
    java

    id("com.dorkbox.GradleUtils") version "1.8"
    id("com.dorkbox.CrossCompile") version "1.1"
    id("com.dorkbox.Licensing") version "1.4.2"
    id("com.dorkbox.VersionUpdate") version "1.6.1"
    id("com.dorkbox.GradlePublish") version "1.2"

    kotlin("jvm") version "1.3.31"
}

object Extras {
    // set for the project
    const val description = "Lightweight, extremely fast, and zero-gc message/event bus for Java 6+"
    const val group = "com.dorkbox"
    const val version = "2.2"

    // set as project.ext
    const val name = "MessageBus"
    const val id = "MessageBus"
    const val vendor = "Dorkbox LLC"
    const val vendorUrl = "https://dorkbox.com"
    const val url = "https://git.dorkbox.com/dorkbox/MessageBus"
    val buildDate = Instant.now().toString()

    val JAVA_VERSION = JavaVersion.VERSION_1_6.toString()
}

///////////////////////////////
/////  assign 'Extras'
///////////////////////////////
GradleUtils.load("$projectDir/../../gradle.properties", Extras)
GradleUtils.fixIntellijPaths()


licensing {
    license(License.APACHE_2) {
        author(Extras.vendor)
        url(Extras.url)
        note(Extras.description)
    }

    license("Dorkbox Utils", License.APACHE_2) {
        author(Extras.vendor)
        url("https://git.dorkbox.com/dorkbox/Utilities")
    }

    license("ASM", License.BSD_3) {
        copyright(2012)
        author("France Télécom")
        url("http://asm.ow2.org")
        note("Bytecode manipulation framework and utilities")
    }

    license("LMAX Disruptor", License.APACHE_2) {
        copyright(2011)
        author("LMAX Ltd")
        url("https://github.com/LMAX-Exchange/disruptor/")
        note("High Performance Inter-Thread Messaging Library")
    }

    license("Conversant Disruptor", License.APACHE_2) {
        copyright(2011)
        author("Conversant")
        url("https://github.com/conversant/disruptor/")
        note("The highest performing intra-thread transfer mechanism available in Java")
    }

    license("FastThreadLocal", License.BSD_3) {
        copyright (2014)
        author("Lightweight Java Game Library Project")
        author("Riven")
        url("https://github.com/LWJGL/lwjgl3/blob/5819c9123222f6ce51f208e022cb907091dd8023/modules/core/src/main/java/org/lwjgl/system/FastThreadLocal.java")
    }

    license("IntMap", License.APACHE_2) {
        copyright(2013)
        author("Mario Zechner <badlogicgames@gmail.com>")
        author("Nathan Sweet <nathan.sweet@gmail.com>")
        url("http://github.com/libgdx/libgdx/")
    }

    license("ReflectASM", License.BSD_3) {
        copyright(2008)
        author("Nathan Sweet")
        url("https://github.com/EsotericSoftware/reflectasm")
    }

    license("MBassador", License.MIT) {
        copyright(2012)
        author("Benjamin Diedrichsen")
        url("https://github.com/bennidi/mbassador")
    }

    license("SLF4J", License.MIT) {
        copyright(2008)
        author("QOS.ch")
        url("http://www.slf4j.org")
    }
}

sourceSets {
    main {
        java {
            setSrcDirs(listOf("src"))

            // want to include java files for the source. 'setSrcDirs' resets includes...
            include("**/*.java")
        }
    }

    test {
        java {
            setSrcDirs(listOf("test"))

            // want to include java files for the source. 'setSrcDirs' resets includes...
            include("**/*.java")
        }
    }
}

repositories {
    mavenLocal() // this must be first!
    jcenter()
}


///////////////////////////////
//////    Task defaults
///////////////////////////////
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"

    sourceCompatibility = Extras.JAVA_VERSION
    targetCompatibility = Extras.JAVA_VERSION
}

tasks.jar.get().apply {
    manifest {
        // https://docs.oracle.com/javase/tutorial/deployment/jar/packageman.html
        attributes["Name"] = Extras.name

        attributes["Specification-Title"] = Extras.name
        attributes["Specification-Version"] = Extras.version
        attributes["Specification-Vendor"] = Extras.vendor

        attributes["Implementation-Title"] = "${Extras.group}.${Extras.id}"
        attributes["Implementation-Version"] = Extras.buildDate
        attributes["Implementation-Vendor"] = Extras.vendor

        attributes["Automatic-Module-Name"] = Extras.id
    }
}

tasks.compileJava.get().apply {
    println("\tCompiling classes to Java $sourceCompatibility")
}


dependencies {
    implementation("com.dorkbox:Utilities:1.5")

    implementation("com.lmax:disruptor:3.4.2")
    implementation("com.conversantmedia:disruptor:1.2.15")

    implementation("org.ow2.asm:asm:7.1")
    implementation("com.esotericsoftware:reflectasm:1.11.9")

    implementation("org.slf4j:slf4j-api:1.7.26")


    testCompile("junit:junit:4.12")
    testCompile("ch.qos.logback:logback-classic:1.2.3")
}

publishToSonatype {
    groupId = Extras.group
    artifactId = Extras.id
    version = Extras.version

    name = Extras.name
    description = Extras.description
    url = Extras.url

    vendor = Extras.vendor
    vendorUrl = Extras.vendorUrl

    issueManagement {
        url = "${Extras.url}/issues"
        nickname = "Gitea Issues"
    }

    developer {
        id = "dorkbox"
        name = Extras.vendor
        email = "email@dorkbox.com"
    }
}
