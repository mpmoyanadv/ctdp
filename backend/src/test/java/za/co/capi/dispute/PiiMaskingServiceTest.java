package za.co.capi.dispute;

import org.junit.jupiter.api.Test;
import za.co.capi.dispute.application.service.PiiMaskingService;
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.model.UserDomain;
import za.co.capi.dispute.domain.model.UserRole;
import za.co.capi.dispute.presentation.dto.ApiDtos.CustomerSummaryDto;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskingServiceTest {

    private final PiiMaskingService maskingService = new PiiMaskingService();

    @Test
    void masksCustomerPiiForExternalAffiliateAndPreservesForInternalStaff() {
        AppUser customer = new AppUser();
        customer.setId(UUID.randomUUID());
        customer.setFullName("Thabo Mokoena");
        customer.setEmail("customer@capi.test");
        customer.setSaIdNumber("8501015800082");
        customer.setPhoneNumber("+27825550192");
        customer.setAccountNumber("1548920341");
        customer.setRole(UserRole.CUSTOMER);
        customer.setUserDomain(UserDomain.CUSTOMER);

        CustomerSummaryDto affiliateView = maskingService.toCustomerSummary(customer, UserRole.AFFILIATE);
        assertThat(affiliateView.piiMasked()).isTrue();
        assertThat(affiliateView.saIdNumber()).isEqualTo("850101****082");
        assertThat(affiliateView.phoneNumber()).isEqualTo("+2782****192");
        assertThat(affiliateView.accountNumber()).isEqualTo("******0341");
        assertThat(affiliateView.email()).isEqualTo("cu****@capi.test");

        CustomerSummaryDto staffView = maskingService.toCustomerSummary(customer, UserRole.STAFF);
        assertThat(staffView.piiMasked()).isFalse();
        assertThat(staffView.saIdNumber()).isEqualTo("8501015800082");
        assertThat(staffView.accountNumber()).isEqualTo("1548920341");
    }
}

