# Analytics in Mana

<!--- TOC -->

* [Solution](#solution)
* [How to add a new Event](#how-to-add-a-new-event)
* [Forks of Mana](#forks-of-mana)

<!--- END -->

## Solution

Mana is using PostHog to send analytics event.
We ask for the user to give consent before sending any analytics data.

## How to add a new Event

The analytics plan is shared between all Mana clients. To add an Event, please open a PR to this project: https://github.com/matrix-org/matrix-analytics-events

Then, once the PR has been merged, and the library is release, you can update the version of the library in the `build.gradle` file.

## Forks of Mana

Analytics on forks are disabled by default. Please refer to AnalyticsConfig and there implementation to setup analytics on your project.
