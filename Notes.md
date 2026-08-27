ClearView — Media & Todo Feature Implementation

Use the existing ClearView Android project and current architecture. Do not rewrite working features unnecessarily. Before making changes, inspect the existing Media, Todo, Calendar, playlist, filter, feed-refresh, and watched-state implementations and integrate these changes into the existing architecture.

The attached screenshots are the visual reference for the current Media UI. Preserve the existing visual style, spacing, typography, colors, and interaction patterns unless a change below specifically requires otherwise.

1. Makkah Live Backup Channel

Add a backup Makkah/Al-Quran live source:

Channel: "AlQuran4k القرآن الكريم"

Make it available as a fallback for the existing Makkah Live functionality. It should gracefully handle unavailable streams and not break the existing live source.

---

TODO SYSTEM

2. Permanent Todo History

Todo history must be permanent and must never be overwritten simply because a Todo was edited.

Example:

A Todo is created on August 10:

Study Quran
Time: 7:00 PM
Duration: 2 hours

On August 20, the user edits it:

Time: 8:00 PM
Duration: 1 hour

The system must retain the previous state/history from August 10.

The Todo's history should record events such as:

Created
Edited
Time changed
Duration changed
Status changed
Attempted
Completed
Snoozed
Deleted

Each historical entry should preserve the relevant old values and timestamp.

Delete behavior

When deleting a Todo that has historical records, do NOT immediately destroy its history.

The delete confirmation sheet/dialog must ask something similar to:

Delete Todo?

This Todo has previous history.

Delete:
○ Todo only
○ Todo + all previous history

Cancel

The exact UI should follow the existing app style.

Deleting the Todo itself must not automatically delete its history unless the user explicitly chooses that option.

---

TODO TYPES

Add support for three Todo types.

3. Normal Todo

Simple completion flow:

Todo
 ↓
Complete

Example:

Read 10 pages

The user clicks Complete and it becomes completed.

---

4. Attempted Todo

Attempted Todos must have an intermediate state:

Todo
 ↓
Attempted
 ↓
Completed

For example:

Study mathematics

The user can first mark:

Attempted

and later:

Completed

History must record both state changes with timestamps.

The UI should clearly distinguish:

Not started
Attempted
Completed

---

5. Time-Based Todo

Support duration-based tasks.

Example:

Study
Duration: 3 hours

If the user works for one hour and marks one hour completed:

Total:     3h
Completed: 1h
Remaining: 2h

The user should be able to update the completed/remaining time while the Todo is active.

For example:

+30 min
-15 min
Set time

Do not lose previous time changes. Record them in Todo history.

Example history:

Created: 3h
+1h completed
Remaining: 2h
+30m completed
Remaining: 1h 30m

Time-based Todos must remain consistent even when edited later.

---

6. Time-Based Todo Snooze

Snooze options for a time-based Todo must respect its valid time range.

Example:

Todo:
Study
Start: 5:00 PM
End: 8:00 PM

If the current time is 6:30 PM, do not show snooze options that would move the Todo outside its valid range.

The snooze sheet should dynamically generate only valid times.

For example, valid options could be:

6:45 PM
7:00 PM
7:15 PM
7:30 PM

but not:

9:00 PM
Tomorrow 10:00 AM

when those values contradict the Todo's configured time range.

The exact available options should be calculated from the Todo's start/end range and current time.

---

7. Calendar Day → Bar Graph

In the Calendar view:

When the user taps a specific day, show a bar graph/statistical visualization for that day.

The graph should summarize Todo activity for the selected date.

Possible data:

Completed
Attempted
Incomplete
Time spent
Time remaining

For time-based Todos, show actual completed duration.

Example:

Monday, Aug 24

Completed       ██████████
Attempted       █████
Incomplete      ██

Study:          2h 15m / 3h
Reading:        45m / 1h

Use the existing application design language rather than introducing an unrelated chart style.

The selected day should update the graph immediately without requiring navigation away from the Calendar.

---

MEDIA

8. Instagram Reels + Image Posts

Add Instagram support to the Media system.

The user should be able to add an Instagram profile using:

@username

or an Instagram profile URL.

Support:

Instagram Reels
Instagram image/photo posts

Use the most reliable available third-party/API/RSS approach compatible with the existing backend.

Prefer public-profile sources and design the implementation so the third-party service can be replaced later without rewriting the Media UI.

Do not continuously poll Instagram.

Cache retrieved results in the backend.

---

9. Different Visual Identity for Instagram Channels

In the channel/avatar row shown in the screenshots, Instagram sources must be visually distinguishable from YouTube sources.

For example:

YouTube channel → existing channel presentation
Instagram → same general avatar style but with a different border/accent

Use a subtle, consistent border/accent difference rather than redesigning the entire channel card.

The user should immediately know:

YouTube source
Instagram source

while the overall Media UI remains visually consistent.

---

10. Instagram Filters

The existing Filter UI currently contains YouTube-based options.

Add Instagram-specific filtering.

At minimum support:

Instagram
    Reels
    Image Posts

The filter system should understand the platform/source type.

The resulting filter model should support combinations such as:

All
YouTube
Instagram

YouTube:
    Videos
    Shorts

Instagram:
    Reels
    Image Posts

Do not break existing YouTube filters.

---

11. Unified Feed Model

The feed system should support different media types in one unified data model.

For example:

Video
Short
Instagram Reel
Instagram Image

Each feed item should contain enough metadata to identify:

platform
source/channel
media type
title/caption
thumbnail/image
URL
published time
watched/read status

This will allow common searching, filtering, sorting, and source navigation.

---

12. Search Must Return Channels

Currently, searching the Media feed should not only return individual media items.

When the user searches for a channel/source, also return the channel itself.

Example:

The user searches:

Safina Society

The search results should show:

Safina Society
YouTube Channel
[Channel avatar]

Latest content...

The user can tap the channel card.

When tapped, navigate to that channel's dedicated feed.

That page should show:

Channel header
Channel avatar
Platform
Remove channel
Filters
Channel's complete feed

The behavior should be equivalent to selecting the channel icon from the top channel row.

---

13. Channel Navigation Consistency

The Media screen currently has:

All
Playlists
Channel 1
Channel 2
Channel 3
...

When the user taps a channel icon, they get that channel's feed and filters.

Search must use exactly the same underlying channel-feed screen/state.

Do not create a second independent implementation for search results.

For example:

Top channel icon
        ↓
Channel Feed Screen

Search result channel card
        ↓
Same Channel Feed Screen

This ensures filters, removal, pagination, and feed behavior stay consistent.

---

14. Feed Updates Without Excessive Requests

The user should see newly uploaded content reasonably quickly.

However:

Do NOT continuously poll YouTube/Instagram.

Implement an intelligent refresh strategy.

Suggested approach:

App open
    ↓
Check cached feed age
    ↓
Only fetch when refresh interval has expired
    ↓
Cache result

Use appropriate background refresh mechanisms where already supported by the project.

Possible strategy:

- Refresh immediately when the Media screen is opened if cached data is stale.
- Use a reasonable minimum refresh interval.
- Deduplicate requests.
- Never start multiple requests for the same source simultaneously.
- Cache successful results.
- Use exponential/backoff behavior after failures.
- Respect API/RSS rate limits.

The goal is:

New uploads appear relatively quickly
WITHOUT
constant requests that could trigger rate limiting/blocking.

Avoid polling every few seconds.

---

PLAYLIST BUGS

15. Playlist Continuous Loading Bug

There is currently a bug where an added playlist can remain in a continuous loading state.

This appears to happen especially after adding many videos continuously from the device.

Investigate the actual cause rather than just adding a UI timeout.

Check for:

Concurrent requests
Duplicate playlist additions
Race conditions
StateFlow updates
Loading state not reset
Pagination state
Coroutine cancellation
Database/file persistence
Duplicate IDs
Failed requests leaving `isLoading = true`

The final state must always transition correctly:

Loading
→ Success

or:

Loading
→ Error

Never leave the playlist permanently stuck in loading.

If multiple videos/playlists are added quickly, requests/state updates should be serialized or safely coordinated.

---

WATCHED / UNWATCHED BUG

16. Short Remains in Feed After Being Watched

There is a bug where a Short can remain visible in the live feed even though:

Filter = Unwatched

This usually happens when:

User watches one Short
User does not scroll
User navigates back

The feed still displays that Short despite its watched state changing.

Fix the source of truth for watched state.

When a video/Short is watched:

Persistent watched state
        ↓
Feed item state
        ↓
Current filter
        ↓
UI

must all become synchronized.

When returning to the Media screen, the feed must be recomputed from the latest watched state rather than relying on a stale list.

For an "Unwatched" filter:

watched = true

must immediately exclude that item.

Do not require the user to manually refresh.

Also make sure that watching one item does not incorrectly mark neighboring Shorts as watched.

---

ARCHITECTURE REQUIREMENTS

Keep the existing architecture and conventions of the project.

Before implementation, inspect:

Media UI
Media ViewModel/state
Repository/network layer
Feed models
Channel model
Playlist model
Filter model
Todo model
Todo persistence
Todo ViewModel
Calendar UI
Watched-state persistence
Background refresh

Avoid duplicating business logic.

Prefer:

single source of truth
immutable UI state
repository-driven data
proper coroutine cancellation
stable IDs
cached network data

For network/media sources, separate source-specific fetching from the common feed model.

Conceptually:

YouTubeSource
InstagramSource
       ↓
FeedRepository
       ↓
Unified FeedItem
       ↓
Filtering/Search
       ↓
Media UI

---

DATA MIGRATION

Existing users already have:

Todos
Todo history/state
Channels
Playlists
Watched videos
Filters

Do not wipe existing data.

If models/storage need to change, implement safe migration/default values.

Existing YouTube channels and playlists must continue working after the update.

---

UI REQUIREMENTS

Use the screenshots as the visual reference.

Preserve:

- dark theme
- existing typography
- rounded cards
- existing channel/avatar row
- current bottom navigation
- existing Media layout
- existing filter UI style
- existing spacing and proportions

New Instagram elements should look native to the existing Media screen rather than feeling like a separate design system.

New Todo functionality should similarly use the existing Todo sheet/card/dialog components where possible.

---

ACCEPTANCE TESTS

Verify all of the following before considering the work complete.

Todos

Create Normal Todo → Complete
Create Attempted Todo → Attempt → Complete
Create Time Todo → Complete partial duration
Increase/decrease completed duration
Edit Todo after several days
Verify old history remains
Delete Todo → verify history deletion choice appears
Snooze time Todo → invalid times are not offered
Tap Calendar day → bar graph appears

Media

Add YouTube channel
Add Instagram profile
Receive Instagram image posts
Receive Instagram Reels
Instagram source visually differs from YouTube
Instagram filters work
Search channel name
Search result shows channel card even without current uploads
Tap channel card
Open same channel feed used by top channel row
Remove channel
Refresh feeds without continuous requests

Playlist

Add many videos quickly
Add multiple playlists
Verify loading terminates
Verify failures do not leave permanent loading state

Watched state

Open Unwatched filter
Watch a Short
Navigate back without scrolling
Verify watched Short disappears from Unwatched feed
Verify neighboring Shorts remain unchanged
Reopen Media
Verify watched state remains correct

Important

Do not simply patch symptoms. Find the underlying state-management, persistence, concurrency, or caching problems causing the existing bugs.

Do not remove existing functionality to make the new features work.

Do not continuously poll external services.

Do not create duplicate channel-feed implementations.

Do not lose existing Todo history.

Implement the features incrementally, test each subsystem, and keep the existing ClearView architecture stable.