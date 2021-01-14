package com.salesforce.slack.swarm.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class Review {

    private Long id;
    private String author;
    private List<Integer> changes;
    private List<Integer> comments;
    private List<Integer> commits;
    private List<String> commitStatus;
    private String deployStatus;
    private String description;
    private Map<String, Object> participants;
    private Boolean pending;
    private String state;
    private String stateLabel;
    private String testStatus;
    private String type;
    private Long created;
    private Long updated;

}
