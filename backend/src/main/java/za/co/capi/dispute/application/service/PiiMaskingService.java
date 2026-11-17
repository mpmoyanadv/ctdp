package za.co.capi.dispute.application.service;

import org.springframework.stereotype.Service;
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.model.UserRole;
import za.co.capi.dispute.presentation.dto.ApiDtos.CustomerSummaryDto;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PiiMaskingService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern SA_PHONE_PATTERN =
            Pattern.compile("(?<!\\d)(?:\\+27|0)[1-9]\\d{8}(?!\\d)");
    private static final Pattern SA_ID_PATTERN =
            Pattern.compile("(?<!\\d)\\d{13}(?!\\d)");
    private static final Pattern ACCOUNT_PATTERN =
            Pattern.compile("(?<!\\d)\\d{10,12}(?!\\d)");

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

    public String maskFreeText(String text, AppUser customer, UserRole viewerRole) {
        if (viewerRole != UserRole.AFFILIATE || text == null || text.isBlank()) {
            return text;
        }
        String scrubbed = text;
        if (customer != null) {
            scrubbed = replaceLiteralIgnoreCase(scrubbed, customer.getPhoneNumber(), maskPhone(customer.getPhoneNumber()));
            scrubbed = replaceLiteralIgnoreCase(scrubbed, customer.getSaIdNumber(), maskSaId(customer.getSaIdNumber()));
            scrubbed = replaceLiteralIgnoreCase(scrubbed, customer.getAccountNumber(), maskAccount(customer.getAccountNumber()));
            scrubbed = replaceLiteralIgnoreCase(scrubbed, customer.getEmail(), maskEmail(customer.getEmail()));
            scrubbed = replaceLiteralIgnoreCase(scrubbed, customer.getFullName(), maskName(customer.getFullName()));
        }
        scrubbed = replaceMatches(scrubbed, EMAIL_PATTERN, token -> token.contains("****") ? token : maskEmail(token));
        scrubbed = replaceMatches(scrubbed, SA_PHONE_PATTERN, this::maskPhone);
        scrubbed = replaceMatches(scrubbed, SA_ID_PATTERN, this::maskSaId);
        scrubbed = replaceMatches(scrubbed, ACCOUNT_PATTERN, this::maskAccount);
        return scrubbed;
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

    private static String replaceLiteralIgnoreCase(String text, String target, String replacement) {
        if (text == null || target == null || target.isBlank()) {
            return text;
        }
        return Pattern.compile(Pattern.quote(target.trim()), Pattern.CASE_INSENSITIVE)
                .matcher(text)
                .replaceAll(Matcher.quoteReplacement(replacement));
    }

    private static String replaceMatches(String text, Pattern pattern, UnaryOperator<String> masker) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String masked = masker.apply(matcher.group());
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
