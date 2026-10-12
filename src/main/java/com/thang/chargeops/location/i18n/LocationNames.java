package com.thang.chargeops.location.i18n;

import com.thang.chargeops.location.entity.AdministrativeProvince;
import com.thang.chargeops.location.entity.AdministrativeWard;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.util.StringUtils;

/**
 * VI: Trả về tên hành chính theo ngôn ngữ của request hiện tại (Accept-Language).
 * Cột *_en có thể rỗng nên luôn fallback về tên tiếng Việt gốc.
 *
 * <p>EN: Resolves administrative names for the current request language
 * (Accept-Language). The *_en columns are nullable, so English always falls
 * back to the original Vietnamese value when missing.</p>
 */
public final class LocationNames {

    private LocationNames() {
    }

    public static boolean isEnglish() {
        return "en".equalsIgnoreCase(LocaleContextHolder.getLocale().getLanguage());
    }

    public static String provinceName(AdministrativeProvince province) {
        if (province == null) {
            return null;
        }
        return isEnglish() && StringUtils.hasText(province.getNameEn())
                ? province.getNameEn()
                : province.getName();
    }

    public static String provinceFullName(AdministrativeProvince province) {
        if (province == null) {
            return null;
        }
        return isEnglish() && StringUtils.hasText(province.getFullNameEn())
                ? province.getFullNameEn()
                : province.getFullName();
    }

    public static String wardName(AdministrativeWard ward) {
        if (ward == null) {
            return null;
        }
        return isEnglish() && StringUtils.hasText(ward.getNameEn())
                ? ward.getNameEn()
                : ward.getName();
    }

    public static String wardFullName(AdministrativeWard ward) {
        if (ward == null) {
            return null;
        }
        return isEnglish() && StringUtils.hasText(ward.getFullNameEn())
                ? ward.getFullNameEn()
                : ward.getFullName();
    }
}
