package stillframe42.targetapp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class TargetAppApplication

fun main(args: Array<String>) {
	runApplication<TargetAppApplication>(*args)
}
