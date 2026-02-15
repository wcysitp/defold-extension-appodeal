local appodeal = require "appodeal"

local Provider = {}

function Provider.init(params, callback)
    appodeal.init(params, callback)
end

function Provider.is_fullscreen_available()
    return appodeal.is_interstitial_available()
end

function Provider.show_interstitial(callback)
    appodeal.show_interstitial(callback)
end

function Provider.show_rewarded(callback)
    appodeal.show_rewarded(callback)
end

function Provider.is_rewarded_available()
    return appodeal.is_rewarded_available()
end

return Provider
