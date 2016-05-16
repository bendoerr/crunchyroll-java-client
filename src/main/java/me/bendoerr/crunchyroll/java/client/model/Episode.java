package me.bendoerr.crunchyroll.java.client.model;

import lombok.Value;

@Value
public class Episode {
    private final String show;
    private final String episodeNum;
    private final String episodeName;
}
