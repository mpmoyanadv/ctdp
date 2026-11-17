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
        AppUser customer = sampleCustomer();

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

    @Test
    void masksIdentifiersInsideFreeTextForAffiliateOnly() {
        AppUser customer = sampleCustomer();
        String freeText = "Caller Thabo Mokoena (+27825550192, ID 8501015800082, Acct 1548920341, customer@capi.test) "
                + "also shared alt phone 0821112233 on TXN-2026-9001.";

        String affiliateScrubbed = maskingService.maskFreeText(freeText, customer, UserRole.AFFILIATE);
        assertThat(affiliateScrubbed)
                .doesNotContain("+27825550192")
                .doesNotContain("8501015800082")
                .doesNotContain("1548920341")
                .doesNotContain("customer@capi.test")
                .doesNotContain("Thabo Mokoena")
                .doesNotContain("0821112233")
                .contains("TXN-2026-9001")
                .contains("+2782****192")
                .contains("850101****082")
                .contains("******0341");

        String staffText = maskingService.maskFreeText(freeText, customer, UserRole.STAFF);
        assertThat(staffText).isEqualTo(freeText);
    }

    private static AppUser sampleCustomer() {
        AppUser customer = new AppUser();
        customer.setId(UUID.randomUUID());
        customer.setFullName("Thabo Mokoena");
        customer.setEmail("customer@capi.test");
        customer.setSaIdNumber("8501015800082");
        customer.setPhoneNumber("+27825550192");
        customer.setAccountNumber("1548920341");
        customer.setRole(UserRole.CUSTOMER);
        customer.setUserDomain(UserDomain.CUSTOMER);
        return customer;
    }
}
