package stillframe42.controlplane

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableAsync
class ControlPlaneApplication

fun main(args: Array<String>) {
	runApplication<ControlPlaneApplication>(*args)
}
