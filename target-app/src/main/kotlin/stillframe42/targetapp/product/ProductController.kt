package stillframe42.targetapp.product

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class Product(val id: Long, val name: String, val price: Long)

@RestController
@RequestMapping("/products")
class ProductController {

    private val products = listOf(
        Product(1, "mechanical-keyboard", 129_000),
        Product(2, "vertical-mouse", 59_000),
        Product(3, "4k-monitor", 549_000),
        Product(4, "usb-c-dock", 189_000),
        Product(5, "webcam", 89_000),
    ).associateBy { it.id }

    @GetMapping
    fun findAll(): Collection<Product> = products.values

    @GetMapping("/{id}")
    fun findById(@PathVariable id: Long): Product =
        products[id] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "product $id not found")
}
