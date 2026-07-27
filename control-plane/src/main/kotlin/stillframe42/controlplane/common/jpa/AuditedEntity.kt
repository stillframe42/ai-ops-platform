package stillframe42.controlplane.common.jpa

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.MappedSuperclass
import java.time.Instant
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener

@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class AuditedEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    @CreatedBy
    @Column(name = "created_by", nullable = false)
    var createdBy: String? = null

    @LastModifiedBy
    @Column(name = "updated_by", nullable = false)
    var updatedBy: String? = null
}
