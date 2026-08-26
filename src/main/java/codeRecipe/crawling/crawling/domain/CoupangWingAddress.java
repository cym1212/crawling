package codeRecipe.crawling.crawling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** WING에 등록된 회송지 주소 목록 (의뢰 #8). 에이전트가 입고 실행 시 드로어에서 읽어 동기화한다. */
@Getter
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "coupang_wing_address",
        uniqueConstraints = @UniqueConstraint(name = "uk_wing_address_text", columnNames = "address_text"))
public class CoupangWingAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "wing_address_id")
    private Long wingAddressId;

    @Column(name = "address_text", nullable = false, length = 300)
    private String addressText;              // WING 회송지 드로어 표기 그대로

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;
}
