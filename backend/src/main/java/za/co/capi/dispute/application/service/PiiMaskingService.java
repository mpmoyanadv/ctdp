package za.co.capi.dispute.application.service;

import org.springframework.stereotype.Service;
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.model.UserRole;
import za.co.capi.dispute.presentation.dto.ApiDtos.CustomerSummaryDto;

@Service
public class PiiMaskingService {

    public CustomerSummaryDto toCustomerSummary(AppUser customer, UserRole viewerRole) {
        if (customer == null) {
            return null;
        }
        boolean shouldMask = viewerRole == UserRole.AFFILIATE;
        if (!shouldMask) {
            return new CustomerSummaryDto(
                    customer.getId(),
                    customer.getFullName(),
                    customer.getEmail(),
                    customer.getSaIdNumber(),
                    customer.getPhoneNumber(),
                    customer.getAccountNumber(),
                    false
            );
        }
        return new CustomerSummaryDto(
                customer.getId(),
                maskName(customer.getFullName()),
                maskEmail(customer.getEmail()),
                maskSaId(customer.getSaIdNumber()),
                maskPhone(customer.getPhoneNumber()),
                maskAccount(customer.getAccountNumber()),
                true
        );
    }

    public String maskSaId(String saId) {
        if (saId == null || saId.length() < 9) {
            return "****";
        }
        return saId.substring(0, 6) + "****" + saId.substring(saId.length() - 3);
    }

    public String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "****";
        }
        return phone.substring(0, 5) + "****" + phone.substring(phone.length() - 3);
    }

    public String maskAccount(String account) {
        if (account == null || account.length() < 4) {
            return "****";
        }
        return "******" + account.substring(account.length() - 4);
    }

    public String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "****";
        }
        int atIdx = email.indexOf('@');
        String prefix = email.substring(0, Math.min(2, atIdx));
        return prefix + "****" + email.substring(atIdx);
    }

    public String maskName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "Protected Client";
        }
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].charAt(0) + "****";
        }
        return parts[0].charAt(0) + ". " + parts[parts.length - 1].charAt(0) + "****";
    }
}

