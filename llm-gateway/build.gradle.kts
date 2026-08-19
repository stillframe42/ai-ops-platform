plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "stillframe42"
version = "0.0.1-SNAPSHOT"
description = "AIOps llm-gateway — 모든 LLM 호출의 단일 관문 (라우팅·캐싱·비용·폴백·한도)"

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
	implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
	implementation("org.springframework.ai:spring-ai-starter-model-openai")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("com.bucket4j:bucket4j_jdk17-lettuce:8.14.0")
	implementation("org.springframework.ai:spring-ai-pgvector-store")
	implementation("com.zaxxer:HikariCP")
	runtimeOnly("org.postgresql:postgresql")
	implementation("tools.jackson.module:jackson-module-kotlin")
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
	jvmArgs(
		// JDK 25 JEP 472: netty(lettuce·reactor-netty 전이 의존)의 System::loadLibrary 가
		// 제한 메서드 — 미래 릴리스 차단 예고라 명시 허용 (경고 소거가 아니라 정책 선언).
		"--enable-native-access=ALL-UNNAMED",
		// Mockito 에이전트가 부트스트랩 클래스패스를 덧붙여 CDS 공유 불가 안내가 뜸 — 테스트에선 CDS 무익이라 비활성
		"-Xshare:off",
	)
}
