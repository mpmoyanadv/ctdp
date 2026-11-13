package za.co.capi.dispute;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import za.co.capi.dispute.domain.repository.AppUserRepository;
import za.co.capi.dispute.infrastructure.config.DataSeeder;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DataSeederWithoutDemoProfileTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AppUserRepository userRepository;

    @Test
    void doesNotRegisterOrRunDataSeederWhenDemoProfileIsInactive() {
        assertThat(applicationContext.getBeansOfType(DataSeeder.class)).isEmpty();
        assertThat(userRepository.count()).isZero();
    }
}

