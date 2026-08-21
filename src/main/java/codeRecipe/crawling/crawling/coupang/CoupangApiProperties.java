package codeRecipe.crawling.crawling.coupang;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "coupang.api")
public class CoupangApiProperties {

    private String baseUrl;
    private String accessKey;
    private String secretKey;
    private String vendorId;
}
