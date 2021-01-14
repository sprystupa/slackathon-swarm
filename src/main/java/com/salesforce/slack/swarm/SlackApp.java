package com.salesforce.slack.swarm;

import com.google.gson.Gson;
import com.slack.api.bolt.App;
import com.slack.api.bolt.WebEndpoint;
import com.slack.api.bolt.jetty.SlackAppServer;
import com.slack.api.bolt.response.Response;
import com.slack.api.methods.response.views.ViewsOpenResponse;
import com.slack.api.methods.response.views.ViewsPublishResponse;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.element.BlockElement;
import com.slack.api.model.event.AppHomeOpenedEvent;
import com.slack.api.model.view.View;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import com.salesforce.slack.swarm.model.Review;
import com.salesforce.slack.swarm.model.ReviewsData;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

import static com.slack.api.model.block.Blocks.*;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.*;
import static com.slack.api.model.view.Views.*;

public class SlackApp {

    private final static Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        // App expects env variables (SLACK_BOT_TOKEN, SLACK_SIGNING_SECRET)
        App app = new App();

        app.endpoint(WebEndpoint.Method.POST, "/events", (req, ctx) -> ctx.ackWithJson(req.getRequestBodyAsString()));

        app.command("/hello", (req, ctx) -> ctx.ack(":wave: Hello!"));

        app.command("/changelist", (req, ctx) -> {
            String param = req.getPayload().getText();
            if (StringUtils.isBlank(param)) {
                return ctx.ack(":exclamation: Please provide change list number you want to review");
            }
            ReviewsData reviewsData = readObjectFromJsonFile("reviews.json", ReviewsData.class);
            Optional<Review> reviewOpt = reviewsData.getReviews().stream().filter(review -> param.equals(review.getId().toString())).findFirst();
            return reviewOpt.isPresent() ?
                    ctx.ack(asBlocks(buildCompactLayoutForReview(reviewOpt.get(), new ArrayList<>()))) : ctx.ack(":warning: Review Not Found!");
        });

        Pattern pattern = Pattern.compile("details_[0-9]+");
        app.blockAction(pattern, (req, ctx) -> {
            ReviewsData reviewsData;
            try {
                reviewsData = readObjectFromJsonFile("reviews.json", ReviewsData.class);
            } catch (IOException e) {
                return errorResponse(e.getMessage());
            }
            String actionId = req.getPayload().getActions().get(0).getActionId();
            String reviewId = actionId.substring(actionId.indexOf('_') + 1);
            Optional<Review> reviewOpt = reviewsData.getReviews().stream().filter(review -> reviewId.equals(review.getId().toString())).findFirst();
            Review review = reviewOpt.orElse(null);

            ViewsOpenResponse viewsOpenRes = ctx.client().viewsOpen(r -> r
                    .triggerId(ctx.getTriggerId())
                    .view(buildModalView(review)));

            return viewsOpenRes.isOk() ? ctx.ack() : Response.builder().statusCode(500).body(viewsOpenRes.getError()).build();
        });

        app.event(AppHomeOpenedEvent.class, (payload, ctx) -> {

            ReviewsData myReviewsData;
            ReviewsData participantReviewsData;
            try {
                myReviewsData = readObjectFromJsonFile("reviews.json", ReviewsData.class);
                participantReviewsData = readObjectFromJsonFile("participant_reviews.json", ReviewsData.class);
            } catch (Exception e) {
                return errorResponse(e.getMessage());
            }

            ViewsPublishResponse viewsPublishRes = ctx.client().viewsPublish(r -> r
                    .userId(payload.getEvent().getUser())
                    .view(buildHomeView(myReviewsData, participantReviewsData))
            );

            return viewsPublishRes.isOk() ? ctx.ack() : errorResponse(viewsPublishRes.getError());
        });

        SlackAppServer server = new SlackAppServer(app);
        server.start(); // http://localhost:3000/slack/events
    }

    private static Response errorResponse(String error) {
        return Response.builder().statusCode(500).body(error).build();
    }

    private static View buildHomeView(ReviewsData reviewsData, ReviewsData participantReviewsData) {
        List<LayoutBlock> blocks = new ArrayList<>();
        addReviewsToBlocks("*List of pull-requests opened by you* :tada:", reviewsData, blocks);
        blocks.add(divider());
        blocks.add(divider());
        blocks.add(divider());
        addReviewsToBlocks("*List of pull-requests where you are assigned as reviewer* :tada:", participantReviewsData, blocks);
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
                .notifyOnClose(true)
                .title(viewTitle(title -> title.type("plain_text").text("Review Details").emoji(true)))
                .submit(viewSubmit(submit -> submit.type("plain_text").text("Submit").emoji(true)))
                .close(viewClose(close -> close.type("plain_text").text("Cancel").emoji(true)))
                .blocks(asBlocks(buildLayoutForReview(review)))
        );
    }

    private static void addReviewsToBlocks(String blockName, ReviewsData reviewsData, List<LayoutBlock> blocks) {
        String lastSeen = reviewsData != null && reviewsData.getLastSeen() != null ? reviewsData.getLastSeen().toString() : "Unknown";
        int total = reviewsData != null && reviewsData.getTotalCount() != null ? reviewsData.getTotalCount() : 0;
        blocks.add(section(section -> section.text(markdownText(mt -> mt.text(blockName)))));
        if (reviewsData != null && CollectionUtils.isNotEmpty(reviewsData.getReviews())) {
            reviewsData.getReviews().forEach(review -> buildCompactLayoutForReview(review, blocks));
        }
        blocks.add(divider());
        blocks.add(section(section -> section.text(markdownText(mt -> mt.text("Last seen: " + lastSeen + "\tTotal reviews: " + total)))));
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
                //.accessory()
                )
        );
        LocalDate date = Instant.ofEpochMilli(review.getCreated()).atZone(ZoneId.systemDefault()).toLocalDate();
        blocks.add(context(context -> context
                .elements(asContextElements(
                        imageElement(image -> image.imageUrl("https://api.slack.com/img/blocks/bkb_template_images/task-icon.png").altText("Not Found")),
                        markdownText("Submitted by: *" + review.getAuthor() + "* on " + date.format(DateTimeFormatter.ISO_DATE))
                ))));

        List<BlockElement> actionsList = new ArrayList<>();
        actionsList.add(button(b -> b.text(plainText(pt -> pt.text("View Details"))).value("details_" + review.getId()).actionId("details_" + review.getId())));
        if (review.getState().startsWith("needs")) {
            actionsList.add(button(b -> b.text(plainText(pt -> pt.text("Approve"))).style("primary").value("approve_" + review.getId()).actionId("approve_" + review.getId())));
            actionsList.add(button(b -> b.text(plainText(pt -> pt.text("Decline"))).style("danger").value("decline_" + review.getId()).actionId("decline_" + review.getId())));
        }
        blocks.add(actions(actions -> actions.elements(asElements(actionsList.toArray(BlockElement[]::new)))));
        return blocks.toArray(LayoutBlock[]::new);
    }

    private static LayoutBlock[] buildLayoutForReview(Review review) {
        if (review == null) return new LayoutBlock[0];
        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(section(section -> section.text(markdownText(mt -> mt.text(getReviewDescription(review))))));
        return blocks.toArray(LayoutBlock[]::new);
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

    private static <T> T readObjectFromJsonFile(String fileName, Class<T> objectClass) throws IOException {
        Path filePath = Paths.get(ClassLoader.getSystemResource(fileName).getPath());
        try (Reader reader = Files.newBufferedReader(filePath)) {
            return GSON.fromJson(reader, objectClass);
        }
    }

}