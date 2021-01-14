package com.salesforce.slack.swarm.model;

import lombok.Data;

import java.util.List;

@Data
public class ReviewsData {

    private Integer lastSeen;
    private List<Review> reviews;
    private Integer totalCount;

}
