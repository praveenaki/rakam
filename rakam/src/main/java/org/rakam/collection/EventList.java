package org.rakam.collection;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.rakam.server.http.annotations.ApiParam;

import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

public class EventList {
    public final Event.EventContext api;
    public final String project;
    public final List<Event> events;

    @JsonCreator
    public EventList(@ApiParam(name = "api") Event.EventContext api,
                     @ApiParam(name = "project") String project,
                     @ApiParam(name = "events") List<Event> events) {
        this.project = checkNotNull(project, "project parameter is null");
        this.events = checkNotNull(events, "events parameter is null");
        this.api = checkNotNull(api, "api is null");
    }

    @Override
    public String toString() {
        return "EventList{" +
                "api=" + api +
                ", project='" + project + '\'' +
                ", events=" + events +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventList)) return false;

        EventList eventList = (EventList) o;

        if (!api.equals(eventList.api)) return false;
        if (!project.equals(eventList.project)) return false;
        return events.equals(eventList.events);

    }

    @Override
    public int hashCode() {
        int result = api.hashCode();
        result = 31 * result + project.hashCode();
        result = 31 * result + events.hashCode();
        return result;
    }
}