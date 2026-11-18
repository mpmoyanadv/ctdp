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
@ActiveProfiles({"demo", "test"})
class DataSeederWithDemoProfileTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AppUserRepository userRepository;

    @Test
    void registersAndRunsDataSeederWhenDemoProfileIsActive() {
        assertThat(applicationContext.getBeansOfType(DataSeeder.class)).hasSize(1);
        assertThat(userRepository.count()).isEqualTo(8);
        assertThat(userRepository.findByEmailIgnoreCase("customer@capi.test")).isPresent();
        assertThat(userRepository.findByEmailIgnoreCase("customer3@capi.test")).isPresent();
        assertThat(userRepository.findByEmailIgnoreCase("customer4@capi.test")).isPresent();
    }
}

