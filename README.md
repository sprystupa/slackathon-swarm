# Slack for Swarm
By: Siarhei Prystupa & Sergii Puliaiev

## Welcome to Slack for Swarm

At Salesforce collaboration between teams and team members is very important. Collaboration is
a broad topic, but I would like to speak about it's small part - Code Collaboration, which includes code review process.
Code review has a high impact on a product quality and team members performance. The sooner code is reviewed and discussed
the better results will be in overall team performance and delivery speed.

The problem we all as developers might be facing is that Code Review request email notifications can be buried in a huge pile of emails that
land in your inbox every day and there is no reliable notification mechanism. In addition to that code review requests you participate
in or you are the author of can be scattered across your mailbox and you need to spend valuable time trying to find all of them.

Swarm integration with Slack can help solving this issue. Slack has a good notification system and Application Home where
Code Review requests can be organized according some criteria (which is highly customizable by various filters).
In addition to Home page Slack commands allow developer to get various information from Swarm on demand, like
Code Review Request numbers, Files being a part of requests and information about Swarm users (like full name and work email address)

Slack for Swarm allows you to do the following

- View Swarm Review requests on your Slack Home. Various filters can help you customize what you want to see on Slack Home
- Run commands on demand to get information about Code Review, Perforce User  
- Get realtime notifications from Swarm when code review is requested, approved/declined, etc.

A recording of the Demo for Slack for Swarm: https://drive.google.com/file/d/1B_RQA3jmGomaT0M8P8jshOWefMtecIgs/view?usp=sharing

## How to setup Slack APP

### Socket Mode
Go to 'Socket Mode' and enable the Socket Mode

### Basic Information
1.  Go to 'Basic Information' and save the value of Signing Secret from section Basic Information :
    `Signing Secret`
    to put into variable **SLACK_SIGNING_SECRET**


2. Generate 'App-Level Tokens' with some name, like 'SwarmIntToken' and Scope=connections:write
   Save it's value to put into variable **SLACK_SOCKET_APP_TOKEN**


3. Set in the 'Display Information' values:
* **App name** = Swarm4Slack
* **Short description** = Salesforce Swarm for Slack


### Slash Commands configuration
Go to 'Slash Commands' and configure the following Commands:
1. **/user**
* Description: Finds information about P4 user by username
* Hint: sprystupa
2. **/changelist**
* Description: Find information about Code Review request
* Hint: 123456789

### Home Tab Configuration
1. Go to 'App Home Section' and enable Home Tab
2. Set the 'App Display Name' to
   `Swarm For Slack`
3. Set the 'Default username' to
   `Swarm4Slack`

### Interactivity & Shortcuts configuration
Go to 'Interactivity & Shortcuts' and enable it

### OAuth & Permissions configuration
Go to 'OAuth & Permissions' and add the following scopes:
* app_mentions:read
* chat:write
* commands

### Event Subscriptions configuration
1.  Go to 'Event Subscriptions' and enable it
2. Subscribe bot for the following events:
* app_home_opened

### Install App into workspace
Save the value of
`Bot User OAuth Access Token`
to put into variable **SLACK_BOT_TOKEN**

### Use your saved credentials for app
Copy the saved values into the execution configuration for your NodeJS app:
* **SLACK_SIGNING_SECRET**
* **SLACK_SOCKET_APP_TOKEN**
* **SLACK_BOT_TOKEN**