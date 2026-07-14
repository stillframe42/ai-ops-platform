plugins {
	// 로컬 JDK 버전과 무관하게 빌드에 필요한 toolchain(Java 25)을 자동 프로비저닝
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "target-app"
