package gov.openfiling.api.mapper;

import gov.openfiling.api.dto.InventorDto;
import gov.openfiling.api.model.ApplicationInventor;

import java.util.List;
import java.util.Locale;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface InventorMapper {

    InventorDto toDto(ApplicationInventor inventor);

    List<InventorDto> toDtos(List<ApplicationInventor> inventors);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "application", ignore = true)
    @Mapping(target = "sequence", ignore = true)
    ApplicationInventor toEntity(InventorDto dto);

    List<ApplicationInventor> toEntities(List<InventorDto> dtos);

    /** Trims names and upper-cases ISO country codes; blank optional fields become null. */
    @AfterMapping
    default void normalize(@MappingTarget ApplicationInventor inventor) {
        inventor.setGivenName(trim(inventor.getGivenName()));
        inventor.setFamilyName(trim(inventor.getFamilyName()));
        inventor.setCity(blankToNull(inventor.getCity()));
        inventor.setState(blankToNull(inventor.getState()));
        inventor.setCountry(upper(inventor.getCountry()));
        inventor.setCitizenship(upper(inventor.getCitizenship()));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String upper(String value) {
        String v = blankToNull(value);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }
}
