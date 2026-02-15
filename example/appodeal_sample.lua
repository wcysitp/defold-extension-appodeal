local appodeal = require "appodeal"

local M = {}

local function log_event(prefix, event)
    pprint({
        prefix = prefix,
        event = event
    })
end

function M.init(app_key)
    appodeal.init({
        app_key = app_key,
        testing = true,
        log_level = "verbose"
    }, function(event)
        log_event("init", event)
    end)
end

function M.show_interstitial()
    appodeal.show_interstitial(function(event)
        log_event("interstitial", event)
    end)
end

function M.show_rewarded()
    appodeal.show_rewarded(function(event)
        log_event("rewarded", event)
    end)
end

function M.is_interstitial_available()
    return appodeal.is_interstitial_available()
end

function M.is_rewarded_available()
    return appodeal.is_rewarded_available()
end

return M
