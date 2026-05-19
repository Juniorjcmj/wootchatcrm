package com.seucrm.api.lead;

import com.seucrm.domain.lead.Tag;

public record TagInfo(String name, String color) {
    public static TagInfo from(Tag t) {
        return new TagInfo(t.getName(), t.getColor());
    }
}
