package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

/**
 * Outcome of a create/update. Returned as a 200 body (the agent's error handler collapses thrown
 * exceptions to 500, so conflict/not-found travel as data); the operator maps it to an HTTP status.
 */
public enum CommandSaveStatus {
    CREATED,
    UPDATED,
    CONFLICT,
    NOT_FOUND
}
