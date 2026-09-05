package com.judepereira.jupiter.persistence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectCommandEnvironmentAllowlistTests {

    @Test
    void parsesTrimmedUniqueNamesInInputOrder() {
        assertEquals(List.of("PATH", "HOME", "_JUPITER"),
                List.copyOf(Persistence.ProjectView.parseCommandEnvironmentAllowlist(" PATH, HOME,PATH,, _JUPITER ")));
    }

    @Test
    void blankAndNullAreEmpty() {
        assertEquals(Set.of(), Persistence.ProjectView.parseCommandEnvironmentAllowlist(null));
        assertEquals(Set.of(), Persistence.ProjectView.parseCommandEnvironmentAllowlist(" ,  "));
    }

    @Test
    void rejectsMalformedNames() {
        assertThrows(IllegalArgumentException.class,
                () -> Persistence.ProjectView.parseCommandEnvironmentAllowlist("PATH, bad-name"));
    }
}
