package com.salesforce.slack.swarm.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.List;

@Data
public class User {

    @SerializedName(value = "username", alternate = {"user", "User"})
    private String username;

    @SerializedName(value = "type", alternate = "Type")
    private String type;

    @SerializedName(value = "email", alternate = "Email")
    private String email;

    @SerializedName(value = "fullName", alternate = {"fullname", "FullName"})
    private String FullName;

    @SerializedName(value = "reviews", alternate = "Reviews")
    private List<String> reviews;

}
