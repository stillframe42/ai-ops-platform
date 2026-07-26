plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "stillframe42"
version = "0.0.1-SNAPSHOT"
description = "AIOps control-plane — 관제/API/게이트웨이, MCP 도구 서버"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

extra["springAiVersion"] = "2.0.0"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
	implementation("org.springframework.ai:spring-ai-starter-model-openai")
	implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
	implementation("tools.jackson.module:jackson-module-kotlin")
	// Alertmanager webhook → 이상 이벤트 발행 (DAY 17) — 버전은 Boot BOM 관리 (spring-kafka 4.1.0, kafka-clients 4.2.1).
	// Boot 4 는 자동구성이 기술별 모듈로 분리 — 순수 spring-kafka 만으론 KafkaTemplate 빈이 없다 (기동 실측), starter 필수
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
