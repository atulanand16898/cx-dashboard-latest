package com.cxalloy.integration.service;

import com.cxalloy.integration.model.DataProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ProviderContextService {

    public DataProvider currentProvider() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            Object details = authentication.getDetails();
            if (details instanceof String provider && StringUtils.hasText(provider)) {
                try {
                    return DataProvider.fromValue(provider);
                } catch (IllegalArgumentException ignored) {
                    // Fall back to the default provider when older sessions do not carry the claim.
                }
            }
        }
        return DataProvider.CXALLOY;
    }

    public String currentProviderKey() {
        return currentProvider().getKey();
    }

    public boolean matchesCurrentProvider(String provider) {
        if (!StringUtils.hasText(provider)) {
            return currentProvider() == DataProvider.CXALLOY;
        }
        return currentProviderKey().equalsIgnoreCase(provider.trim());
    }
}
