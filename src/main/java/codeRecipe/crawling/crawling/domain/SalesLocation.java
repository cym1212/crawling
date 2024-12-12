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
@Table(name = "sales_location")
public class SalesLocation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long locationId;

    @Column(nullable = false)
    private String locationName; // 판매점 위치 ex) 핫트랙스, 리브로

    @Column
    private String region; // 판매점 지점

    public SalesLocation(String locationName, String region) {
        this.locationName = locationName;
        this.region = region;
    }

    public boolean isSameLocation(String locationName, String region) {
        return this.locationName.equals(locationName) &&
                ((this.region == null && region == null) ||
                        (this.region != null && this.region.equals(region)));
    }
}
