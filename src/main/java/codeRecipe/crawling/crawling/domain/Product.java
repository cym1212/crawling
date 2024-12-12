package codeRecipe.crawling.crawling.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "product")
public class Product  {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long productId;


    @Column(unique = true)
    private String productCode; // 상품 코드 & 바코드

    @Column(nullable = false)
    private String productName; // 상품 이름

    @Column
    private String publisher;


    public boolean isSameProduct(String productName, String publisher) {
        return this.productName.equals(productName) &&
                ((this.publisher == null && publisher == null) ||
                        (this.publisher != null && this.publisher.equals(publisher)));
    }
}
