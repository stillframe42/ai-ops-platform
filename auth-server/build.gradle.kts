plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "stillframe42"
version = "0.0.1-SNAPSHOT"
description = "AIOps auth-server — 서비스 간(M2M) OAuth 2.1 토큰 발급 (Client Credentials, ADR-0016)"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	// Spring Security 7 에 통합된 Authorization Server — 별도 프로젝트 버전 대조 불요 (Boot 4)
	implementation("org.springframework.boot:spring-boot-starter-oauth2-authorization-server")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Mockito 에이전트가 부트스트랩 클래스패스를 덧붙여 CDS 공유 불가 안내가 뜸 — 테스트에선 CDS 무익이라 비활성
	jvmArgs("-Xshare:off")
}
