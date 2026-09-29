package gov.openfiling.api.mapper;

import gov.openfiling.api.dto.PartyRef;
import gov.openfiling.api.dto.UserResponse;
import gov.openfiling.api.model.Role;
import gov.openfiling.api.model.User;

import java.util.Collection;
import java.util.List;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AccountMapper {

    UserResponse toResponse(User user);

    default PartyRef toParty(User user) {
        return user == null ? null : new PartyRef(user.getId(), user.getDisplayName());
    }

    /** Roles in a stable order: INVENTOR, EXAMINER, ADMIN. */
    default List<String> roleNames(Collection<Role> roles) {
        return roles == null ? List.of() : roles.stream().sorted().map(Role::name).toList();
    }
}
