package com.salesforce.slack.swarm;

import com.google.gson.Gson;
import com.salesforce.slack.swarm.model.Review;
import com.salesforce.slack.swarm.model.ReviewDetails;
import com.salesforce.slack.swarm.model.ReviewsData;
import com.salesforce.slack.swarm.model.User;
import com.slack.api.bolt.App;
import com.slack.api.bolt.WebEndpoint;
import com.slack.api.bolt.response.Response;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.methods.response.views.ViewsOpenResponse;
import com.slack.api.methods.response.views.ViewsPublishResponse;
import com.slack.api.methods.response.views.ViewsUpdateResponse;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.composition.OptionObject;
import com.slack.api.model.block.composition.PlainTextObject;
import com.slack.api.model.block.element.BlockElement;
import com.slack.api.model.event.AppHomeOpenedEvent;
import com.slack.api.model.view.View;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

import static com.salesforce.slack.swarm.SlackApp.REVIEW_TYPE.AUTHOR;
import static com.salesforce.slack.swarm.SlackApp.REVIEW_TYPE.PARTICIPANT;
import static com.slack.api.model.block.Blocks.*;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.*;
import static com.slack.api.model.view.Views.*;
import static java.time.format.DateTimeFormatter.ISO_DATE;

@Slf4j
public class SlackApp {

    enum REVIEW_TYPE {
        AUTHOR("I'm author"),
        PARTICIPANT("I'm  participant");

        private final String description;

        REVIEW_TYPE(String description) {
            this.description = description;
        }

        String getDescription() {
            return this.description;
        }
    }

    private final static Gson GSON = new Gson();
    private final static OkHttpClient REST_CLIENT;

    static {
        final Properties props = new Properties();
        String rootDir = System.getProperty("user.home");
        try (FileInputStream fis = new FileInputStream(rootDir + "/blt/config.blt")) {
            props.load(fis);
        } catch (IOException exc) {
            log.error("Could not load properties from config file");
            props.put("p4.user", "fake_user");
            props.put("p4.password", "fake_password");
        }
        REST_CLIENT = createAuthenticatedClient(props.getProperty("p4.user"), props.getProperty("p4.password"));
    }

    public static void main(String[] args) throws Exception {
        App app = new App();

        app.endpoint(WebEndpoint.Method.POST, "/events", (req, ctx) -> ctx.ackWithJson(req.getRequestBodyAsString()));

        app.command("/hello", (req, ctx) -> ctx.ack(":wave: Hello!"));
        app.command("/user", (req, ctx) -> {
            String param = req.getPayload().getText();
            if (StringUtils.isBlank(param)) {
                return ctx.ack(":exclamation: Please type username");
            }
            User user = getUser(param);
            return user != null
                    ? ctx.ack(asBlocks(buildLayoutForUser(user)))
                    : ctx.ack(":warning: User Not Found!");
        });

        app.command("/changelist", (req, ctx) -> {
            String param = req.getPayload().getText();
            if (StringUtils.isBlank(param)) {
                return ctx.ack(":exclamation: Please provide change list number you want to review");
            }
            Review review = getReview(param);
            return review != null
                    ? ctx.ack(asBlocks(buildCompactLayoutForReview(review, new ArrayList<>())))
                    : ctx.ack(":warning: Review Not Found!");
        });

        Pattern pattern = Pattern.compile("details_[0-9]+");
        app.blockAction(pattern, (req, ctx) -> {
            String actionId = req.getPayload().getActions().get(0).getActionId();
            String reviewId = actionId.substring(actionId.indexOf('_') + 1);
            Review review = getReview(reviewId);
            ViewsOpenResponse viewsOpenRes = ctx.client().viewsOpen(r -> r
                    .triggerId(ctx.getTriggerId())
                    .view(buildModalView(review)));

            return viewsOpenRes.isOk()
                    ? ctx.ack()
                    : Response.builder().statusCode(500).body(viewsOpenRes.getError()).build();
        });

        app.blockAction("change_review_type", (req, ctx) -> {
            String selectedOption = req.getPayload().getActions().get(0).getSelectedOption().getValue();
            REVIEW_TYPE reviewType = REVIEW_TYPE.valueOf(selectedOption);

            ReviewsData reviewsData;
            try {
                reviewsData = getChangeList(reviewType);
            } catch (IOException e) {
                return errorResponse("Error retrieving reviews");
            }

            ViewsUpdateResponse viewsUpdateResponse = ctx.client().viewsUpdate(r ->
                    r.viewId(req.getPayload().getView().getId())
                            .view(buildHomeView(reviewType, reviewsData)));

            return viewsUpdateResponse.isOk() ? ctx.ack() : errorResponse(viewsUpdateResponse.getError());
        });

        app.event(AppHomeOpenedEvent.class, (payload, ctx) -> {
            if (!"home".equals(payload.getEvent().getTab())) {
                return ctx.ack();
            }
            ReviewsData reviewsData;
            try {
                reviewsData = getChangeList(AUTHOR);
            } catch (IOException e) {
                return errorResponse("Error retrieving reviews");
            }
            if (payload.getEvent().getView() == null) {
                View view = buildHomeView(AUTHOR, reviewsData);
                ViewsPublishResponse viewsPublishRes = ctx.client().viewsPublish(r -> r
                        .userId(payload.getEvent().getUser())
                        .view(view)
                );
                return viewsPublishRes.isOk() ? ctx.ack() : errorResponse(viewsPublishRes.getError());
            }
            return ctx.ack();
        });
        new SocketModeApp(app).start();
    }

    private static Response errorResponse(String error) {
        return Response.builder().statusCode(500).body(error).build();
    }

    private static View buildHomeView(REVIEW_TYPE reviewType, ReviewsData reviewsData) {
        List<LayoutBlock> blocks = new ArrayList<>();
        addReviewTypesToBlocks(reviewType, blocks);
        addReviewsToBlocks(reviewsData, blocks);
        blocks.add(divider());

        return view(view -> view
                .type("home")
                .blocks(asBlocks(blocks.toArray(LayoutBlock[]::new)))
        );
    }

    private static View buildModalView(Review review) {
        return view(view -> view
                .callbackId("pullrequest-details")
                .type("modal")
                .notifyOnClose(false)
                .title(viewTitle(title -> title.type("plain_text").text("Review Details").emoji(true)))
                .blocks(asBlocks(buildLayoutForReview(review)))
        );
    }

    private static void addReviewTypesToBlocks(REVIEW_TYPE reviewType, List<LayoutBlock> blocks) {
        List<OptionObject> options = new ArrayList<>(2);
        OptionObject option1 = OptionObject.builder()
                .value(AUTHOR.name())
                .text(new PlainTextObject(AUTHOR.getDescription(), false))
                .build();
        OptionObject option2 = OptionObject.builder()
                .value(PARTICIPANT.name())
                .text(new PlainTextObject(PARTICIPANT.getDescription(), false))
                .build();
        options.add(option1);
        options.add(option2);
        OptionObject initialOption;
        switch (reviewType) {
            case AUTHOR:
                initialOption = option1;
                break;
            case PARTICIPANT:
                initialOption = option2;
                break;
            default:
                initialOption = null;
        }
        blocks.add(section(section -> section
                        .text(markdownText(mt -> mt.text("*Review request type:*")))
                        .accessory(staticSelect(staticSelect -> staticSelect
                                .actionId("change_review_type").options(options).initialOption(initialOption)))
                )
        );
    }

    private static void addReviewsToBlocks(ReviewsData reviewsData, List<LayoutBlock> blocks) {
        if (reviewsData == null || CollectionUtils.isEmpty(reviewsData.getReviews())) return;

        String lastSeen = reviewsData.getLastSeen() != null ? reviewsData.getLastSeen().toString() : "Unknown";
        int total = reviewsData.getTotalCount() != null ? reviewsData.getTotalCount() : 0;
        blocks.add(section(section -> section.text(markdownText(mt -> mt.text("*Review requests*")))));
        reviewsData.getReviews().forEach(review -> buildCompactLayoutForReview(review, blocks));
        blocks.add(divider());
        blocks.add(section(section -> section.text(
                markdownText(mt -> mt.text("Last seen: " + lastSeen + "\tTotal reviews: " + total))))
        );
    }

    private static LayoutBlock[] buildCompactLayoutForReview(Review review, List<LayoutBlock> blocks) {
        if (review == null) return new LayoutBlock[0];
        String ln = System.lineSeparator();
        blocks.add(divider());
        String sb = "*Change List:* " + "<https://swarm.soma.salesforce.com/reviews/" + review.getId() + "| :link: " + review.getId() + ">" +
                ln + "*Description:* " + review.getDescription() +
                ln + "*Status:* " + review.getStateLabel();
        blocks.add(section(section -> section
                        .text(markdownText(mt -> mt.text(sb)))
                        .accessory(imageElement(image -> image
                                .imageUrl("https://swarm.workshop.perforce.com/view/guest/perforce_software/slack/main/images/60x60-Helix-Bee.png")
                                .altText("Helix Swarm Bee")))
                )
        );
        LocalDate date = Instant.ofEpochMilli(review.getCreated()).atZone(ZoneId.systemDefault()).toLocalDate();
        blocks.add(context(context -> context
                .elements(asContextElements(
                        imageElement(image ->
                                image.imageUrl("https://api.slack.com/img/blocks/bkb_template_images/task-icon.png")
                                        .altText("Changelist")
                        ),
                        markdownText("Submitted by: *" + review.getAuthor() + "* on " + date.format(ISO_DATE))
                ))));

        List<BlockElement> actionsList = new ArrayList<>();
        actionsList.add(button(b -> b.text(plainText(pt -> pt.text("View Details"))).value("details_" + review.getId()).actionId("details_" + review.getId())));
        if (review.getState().startsWith("needs")) {
            actionsList.add(button(b -> b.text(plainText(pt -> pt.text("Approve")))
                    .style("primary").value("approve_" + review.getId()).actionId("approve_" + review.getId())));
            actionsList.add(button(b -> b.text(plainText(pt -> pt.text("Decline")))
                    .style("danger").value("decline_" + review.getId()).actionId("decline_" + review.getId())));
        }
        blocks.add(actions(actions -> actions.elements(asElements(actionsList.toArray(BlockElement[]::new)))));
        return blocks.toArray(LayoutBlock[]::new);
    }

    private static LayoutBlock[] buildLayoutForUser(User user) {
        if (user == null) return new LayoutBlock[0];
        String ln = System.lineSeparator();
        String text = "*Username:* " + user.getUsername()
                + ln + "*Email:* " + user.getEmail()
                + ln + "*Full Name:* " + user.getFullName()
                + ln + "*Reviews:* " + user.getReviews();
        return new LayoutBlock[]{
                section(section -> section.text(markdownText(mt -> mt.text(text))))
        };
    }

    private static LayoutBlock[] buildLayoutForReview(Review review) {
        if (review == null) return new LayoutBlock[0];
        return new LayoutBlock[]{
                section(section -> section.text(markdownText(mt -> mt.text(getReviewDescription(review)))))
        };
    }

    private static String getReviewDescription(Review review) {
        Field[] fields = review.getClass().getDeclaredFields();
        StringBuilder sb = new StringBuilder();
        String ln = System.lineSeparator();
        Arrays.stream(fields).forEach(field -> {
            try {
                field.setAccessible(true);
                sb.append(ln).append("*").append(field.getName()).append(":* ").append(field.get(review));
            } catch (Exception ignored) {

            }
        });
        return sb.toString();
    }

    private static Review getReview(String number) throws IOException {
        String url = "https://swarm.soma.salesforce.com/api/v9/reviews/" + number;
        okhttp3.Response response = makeApiGetCall(url);
        ReviewDetails reviewDetails = null;
        if (response.body() != null) {
            reviewDetails = GSON.fromJson(Objects.requireNonNull(response.body()).charStream(), ReviewDetails.class);
        }
        response.close();
        return reviewDetails != null ? reviewDetails.getReview() : null;
    }

    private static ReviewsData getChangeList(REVIEW_TYPE reviewType) throws IOException {
        String url = "https://swarm.soma.salesforce.com/api/v9/reviews?max=5&";
        switch (reviewType) {
            case PARTICIPANT:
                url += "participants=sprystupa";
                break;
            case AUTHOR:
            default:
                url += "author=sprystupa";
        }
        okhttp3.Response response = makeApiGetCall(url);
        ReviewsData reviewsData = null;
        if (response.body() != null) {
            reviewsData = GSON.fromJson(Objects.requireNonNull(response.body()).charStream(), ReviewsData.class);
        }
        response.close();
        return reviewsData;
    }

    private static User getUser(String username) throws IOException {
        okhttp3.Response response = makeApiGetCall("https://swarm.soma.salesforce.com/api/v9/users?users=" + username);
        User user = null;
        if (response.body() != null) {
            User[] users = GSON.fromJson(Objects.requireNonNull(response.body()).charStream(), User[].class);
            if (users.length > 0) user = users[0];
        }
        response.close();
        return user;
    }

    private static okhttp3.Response makeApiGetCall(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();
        return REST_CLIENT.newCall(request).execute();
    }

    private static OkHttpClient createAuthenticatedClient(String username, String password) {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            String credential = Credentials.basic(username, password);
            Request request = chain.request().newBuilder().addHeader("Authorization", credential).build();
            return chain.proceed(request);
        }).build();
    }

}