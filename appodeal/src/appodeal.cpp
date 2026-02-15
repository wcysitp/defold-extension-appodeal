#define DLIB_LOG_DOMAIN "Appodeal"
#include <dmsdk/dlib/log.h>
#include <dmsdk/extension/extension.h>
#include <dmsdk/graphics/graphics.h>
#include <dmsdk/script/script.h>

#include <queue>
#include <string>
#include <mutex>

#if defined(DM_PLATFORM_ANDROID)
#include <jni.h>
#endif

namespace
{
    const char* LUA_MODULE_NAME = "appodeal";

    enum EventChannel
    {
        EVENT_INIT = 0,
        EVENT_INTERSTITIAL,
        EVENT_REWARDED,
    };

    struct CallbackEvent
    {
        EventChannel m_Channel;
        std::string m_Event;
        bool m_Success;
        std::string m_Error;
        bool m_Rewarded;
        double m_Amount;
        std::string m_Currency;
    };

    struct AppodealContext
    {
        dmScript::LuaCallbackInfo* m_InitCallback;
        dmScript::LuaCallbackInfo* m_InterstitialCallback;
        dmScript::LuaCallbackInfo* m_RewardedCallback;
        std::queue<CallbackEvent> m_Events;
        std::mutex m_EventsMutex;

#if defined(DM_PLATFORM_ANDROID)
        struct Jni
        {
            JavaVM* m_JavaVm;
            jclass m_Class;
            jmethodID m_Initialize;
            jmethodID m_IsInterstitialAvailable;
            jmethodID m_ShowInterstitial;
            jmethodID m_IsRewardedAvailable;
            jmethodID m_ShowRewarded;
        } m_Jni;
#endif
    };

    AppodealContext g_Appodeal;

    static void DestroyCallback(dmScript::LuaCallbackInfo** callback)
    {
        if (*callback != 0x0)
        {
            dmScript::DestroyCallback(*callback);
            *callback = 0x0;
        }
    }

    static void EnqueueEvent(const CallbackEvent& event)
    {
        std::lock_guard<std::mutex> lock(g_Appodeal.m_EventsMutex);
        g_Appodeal.m_Events.push(event);
    }

    static void PushEventTable(lua_State* L, const CallbackEvent& event)
    {
        lua_newtable(L);

        lua_pushboolean(L, event.m_Success ? 1 : 0);
        lua_setfield(L, -2, "success");

        lua_pushstring(L, event.m_Event.c_str());
        lua_setfield(L, -2, "event");

        if (!event.m_Error.empty())
        {
            lua_pushstring(L, event.m_Error.c_str());
            lua_setfield(L, -2, "error");
        }

        if (event.m_Channel == EVENT_INTERSTITIAL)
        {
            lua_pushstring(L, "interstitial");
            lua_setfield(L, -2, "ad_type");
        }
        else if (event.m_Channel == EVENT_REWARDED)
        {
            lua_pushstring(L, "rewarded");
            lua_setfield(L, -2, "ad_type");

            lua_pushboolean(L, event.m_Rewarded ? 1 : 0);
            lua_setfield(L, -2, "rewarded");

            if (event.m_Amount > 0.0)
            {
                lua_pushnumber(L, event.m_Amount);
                lua_setfield(L, -2, "amount");
            }

            if (!event.m_Currency.empty())
            {
                lua_pushstring(L, event.m_Currency.c_str());
                lua_setfield(L, -2, "currency");
            }
        }
    }

    static void InvokeCallback(dmScript::LuaCallbackInfo* callback, const CallbackEvent& event)
    {
        if (callback == 0x0)
            return;

        lua_State* L = dmScript::GetCallbackLuaContext(callback);
        DM_LUA_STACK_CHECK(L, 0);

        if (dmScript::SetupCallback(callback) != 0)
        {
            dmLogError("Failed to setup callback");
            return;
        }

        PushEventTable(L, event);
        int ret = dmScript::PCall(L, 1, 0);
        dmScript::TeardownCallback(callback);

        if (ret != 0)
        {
            dmLogError("Callback execution failed: %s", lua_tostring(L, -1));
            lua_pop(L, 1);
        }
    }

    static bool IsInterstitialTerminal(const std::string& event)
    {
        return event == "show_failed" || event == "closed" || event == "expired";
    }

    static bool IsRewardedTerminal(const std::string& event)
    {
        return event == "show_failed" || event == "closed" || event == "expired";
    }

    static void DispatchEvent(const CallbackEvent& event)
    {
        dmScript::LuaCallbackInfo** callback = 0x0;
        bool destroy = false;

        if (event.m_Channel == EVENT_INIT)
        {
            callback = &g_Appodeal.m_InitCallback;
            destroy = true;
        }
        else if (event.m_Channel == EVENT_INTERSTITIAL)
        {
            callback = &g_Appodeal.m_InterstitialCallback;
            destroy = IsInterstitialTerminal(event.m_Event);
        }
        else if (event.m_Channel == EVENT_REWARDED)
        {
            callback = &g_Appodeal.m_RewardedCallback;
            destroy = IsRewardedTerminal(event.m_Event);
        }

        if (callback != 0x0 && *callback != 0x0)
        {
            InvokeCallback(*callback, event);
            if (destroy)
            {
                DestroyCallback(callback);
            }
        }
    }

    static void FlushEvents()
    {
        for (;;)
        {
            CallbackEvent event;
            bool has_event = false;
            {
                std::lock_guard<std::mutex> lock(g_Appodeal.m_EventsMutex);
                if (!g_Appodeal.m_Events.empty())
                {
                    event = g_Appodeal.m_Events.front();
                    g_Appodeal.m_Events.pop();
                    has_event = true;
                }
            }

            if (!has_event)
                break;

            DispatchEvent(event);
        }
    }

#if defined(DM_PLATFORM_ANDROID)
    const char* JAVA_CLASS_NAME = "com.defold.appodeal.AppodealBridge";

    struct JniEnvScope
    {
        JNIEnv* m_Env;
        bool m_Detach;

        JniEnvScope()
        : m_Env(0x0)
        , m_Detach(false)
        {
        }

        bool Attach()
        {
            JavaVM* vm = g_Appodeal.m_Jni.m_JavaVm;
            if (vm == 0x0)
            {
                vm = (JavaVM*) dmGraphics::GetNativeAndroidJavaVM();
                g_Appodeal.m_Jni.m_JavaVm = vm;
            }

            if (vm == 0x0)
            {
                dmLogError("JavaVM is not available");
                return false;
            }

            jint get_env_result = vm->GetEnv((void**)&m_Env, JNI_VERSION_1_6);
            if (get_env_result == JNI_EDETACHED)
            {
                if (vm->AttachCurrentThread((void**)&m_Env, 0) != JNI_OK)
                {
                    dmLogError("Failed to attach current thread to JVM");
                    return false;
                }
                m_Detach = true;
            }
            else if (get_env_result != JNI_OK || m_Env == 0x0)
            {
                dmLogError("Failed to get JNI environment");
                return false;
            }

            return true;
        }

        ~JniEnvScope()
        {
            if (m_Detach && g_Appodeal.m_Jni.m_JavaVm != 0x0)
            {
                g_Appodeal.m_Jni.m_JavaVm->DetachCurrentThread();
            }
        }
    };

    static void ClearJniException(JNIEnv* env, const char* context)
    {
        if (env->ExceptionCheck())
        {
            dmLogError("JNI exception during %s", context);
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    static jclass LoadClassWithActivityClassLoader(JNIEnv* env, const char* class_name)
    {
        jobject activity = (jobject) dmGraphics::GetNativeAndroidActivity();
        if (activity == 0x0)
        {
            dmLogError("Android activity is null");
            return 0x0;
        }

        jclass activity_class = env->GetObjectClass(activity);
        if (activity_class == 0x0)
        {
            ClearJniException(env, "GetObjectClass(activity)");
            return 0x0;
        }

        jmethodID get_class_loader = env->GetMethodID(activity_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
        if (get_class_loader == 0x0)
        {
            ClearJniException(env, "GetMethodID(getClassLoader)");
            env->DeleteLocalRef(activity_class);
            return 0x0;
        }

        jobject class_loader = env->CallObjectMethod(activity, get_class_loader);
        if (class_loader == 0x0)
        {
            ClearJniException(env, "CallObjectMethod(getClassLoader)");
            env->DeleteLocalRef(activity_class);
            return 0x0;
        }

        jclass class_loader_class = env->FindClass("java/lang/ClassLoader");
        if (class_loader_class == 0x0)
        {
            ClearJniException(env, "FindClass(ClassLoader)");
            env->DeleteLocalRef(class_loader);
            env->DeleteLocalRef(activity_class);
            return 0x0;
        }

        jmethodID load_class = env->GetMethodID(class_loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        if (load_class == 0x0)
        {
            ClearJniException(env, "GetMethodID(loadClass)");
            env->DeleteLocalRef(class_loader_class);
            env->DeleteLocalRef(class_loader);
            env->DeleteLocalRef(activity_class);
            return 0x0;
        }

        jstring class_name_string = env->NewStringUTF(class_name);
        jclass loaded_class = (jclass) env->CallObjectMethod(class_loader, load_class, class_name_string);
        ClearJniException(env, "CallObjectMethod(loadClass)");

        env->DeleteLocalRef(class_name_string);
        env->DeleteLocalRef(class_loader_class);
        env->DeleteLocalRef(class_loader);
        env->DeleteLocalRef(activity_class);
        return loaded_class;
    }

    static bool EnsureJniReady(JNIEnv* env)
    {
        if (g_Appodeal.m_Jni.m_Class != 0x0 &&
            g_Appodeal.m_Jni.m_Initialize != 0x0 &&
            g_Appodeal.m_Jni.m_IsInterstitialAvailable != 0x0 &&
            g_Appodeal.m_Jni.m_ShowInterstitial != 0x0 &&
            g_Appodeal.m_Jni.m_IsRewardedAvailable != 0x0 &&
            g_Appodeal.m_Jni.m_ShowRewarded != 0x0)
        {
            return true;
        }

        if (g_Appodeal.m_Jni.m_Class == 0x0)
        {
            jclass local_class = LoadClassWithActivityClassLoader(env, JAVA_CLASS_NAME);
            if (local_class == 0x0)
            {
                dmLogError("Failed to load Java class: %s", JAVA_CLASS_NAME);
                return false;
            }

            g_Appodeal.m_Jni.m_Class = (jclass) env->NewGlobalRef(local_class);
            env->DeleteLocalRef(local_class);

            if (g_Appodeal.m_Jni.m_Class == 0x0)
            {
                dmLogError("Failed to create global ref for Java class");
                return false;
            }
        }

        g_Appodeal.m_Jni.m_Initialize = env->GetStaticMethodID(g_Appodeal.m_Jni.m_Class, "initialize", "(Ljava/lang/String;ZLjava/lang/String;)Z");
        g_Appodeal.m_Jni.m_IsInterstitialAvailable = env->GetStaticMethodID(g_Appodeal.m_Jni.m_Class, "isInterstitialAvailable", "()Z");
        g_Appodeal.m_Jni.m_ShowInterstitial = env->GetStaticMethodID(g_Appodeal.m_Jni.m_Class, "showInterstitial", "()Z");
        g_Appodeal.m_Jni.m_IsRewardedAvailable = env->GetStaticMethodID(g_Appodeal.m_Jni.m_Class, "isRewardedAvailable", "()Z");
        g_Appodeal.m_Jni.m_ShowRewarded = env->GetStaticMethodID(g_Appodeal.m_Jni.m_Class, "showRewarded", "()Z");

        if (g_Appodeal.m_Jni.m_Initialize == 0x0 ||
            g_Appodeal.m_Jni.m_IsInterstitialAvailable == 0x0 ||
            g_Appodeal.m_Jni.m_ShowInterstitial == 0x0 ||
            g_Appodeal.m_Jni.m_IsRewardedAvailable == 0x0 ||
            g_Appodeal.m_Jni.m_ShowRewarded == 0x0)
        {
            ClearJniException(env, "GetStaticMethodID");
            dmLogError("Failed to resolve one or more Java method IDs");
            return false;
        }

        return true;
    }

    static bool JavaInitialize(const char* app_key, bool testing, const char* log_level)
    {
        JniEnvScope env_scope;
        if (!env_scope.Attach() || !EnsureJniReady(env_scope.m_Env))
            return false;

        JNIEnv* env = env_scope.m_Env;
        jstring j_app_key = env->NewStringUTF(app_key);
        jstring j_log_level = env->NewStringUTF(log_level);

        jboolean result = env->CallStaticBooleanMethod(
            g_Appodeal.m_Jni.m_Class,
            g_Appodeal.m_Jni.m_Initialize,
            j_app_key,
            testing ? JNI_TRUE : JNI_FALSE,
            j_log_level
        );
        ClearJniException(env, "CallStaticBooleanMethod(initialize)");

        env->DeleteLocalRef(j_app_key);
        env->DeleteLocalRef(j_log_level);
        return result == JNI_TRUE;
    }

    static bool JavaIsInterstitialAvailable()
    {
        JniEnvScope env_scope;
        if (!env_scope.Attach() || !EnsureJniReady(env_scope.m_Env))
            return false;

        JNIEnv* env = env_scope.m_Env;
        jboolean result = env->CallStaticBooleanMethod(g_Appodeal.m_Jni.m_Class, g_Appodeal.m_Jni.m_IsInterstitialAvailable);
        ClearJniException(env, "CallStaticBooleanMethod(isInterstitialAvailable)");
        return result == JNI_TRUE;
    }

    static bool JavaShowInterstitial()
    {
        JniEnvScope env_scope;
        if (!env_scope.Attach() || !EnsureJniReady(env_scope.m_Env))
            return false;

        JNIEnv* env = env_scope.m_Env;
        jboolean result = env->CallStaticBooleanMethod(g_Appodeal.m_Jni.m_Class, g_Appodeal.m_Jni.m_ShowInterstitial);
        ClearJniException(env, "CallStaticBooleanMethod(showInterstitial)");
        return result == JNI_TRUE;
    }

    static bool JavaIsRewardedAvailable()
    {
        JniEnvScope env_scope;
        if (!env_scope.Attach() || !EnsureJniReady(env_scope.m_Env))
            return false;

        JNIEnv* env = env_scope.m_Env;
        jboolean result = env->CallStaticBooleanMethod(g_Appodeal.m_Jni.m_Class, g_Appodeal.m_Jni.m_IsRewardedAvailable);
        ClearJniException(env, "CallStaticBooleanMethod(isRewardedAvailable)");
        return result == JNI_TRUE;
    }

    static bool JavaShowRewarded()
    {
        JniEnvScope env_scope;
        if (!env_scope.Attach() || !EnsureJniReady(env_scope.m_Env))
            return false;

        JNIEnv* env = env_scope.m_Env;
        jboolean result = env->CallStaticBooleanMethod(g_Appodeal.m_Jni.m_Class, g_Appodeal.m_Jni.m_ShowRewarded);
        ClearJniException(env, "CallStaticBooleanMethod(showRewarded)");
        return result == JNI_TRUE;
    }

    static std::string JStringToString(JNIEnv* env, jstring value)
    {
        if (value == 0x0)
            return "";

        const char* chars = env->GetStringUTFChars(value, 0);
        std::string result = chars ? chars : "";
        if (chars != 0x0)
            env->ReleaseStringUTFChars(value, chars);
        return result;
    }

    extern "C" JNIEXPORT void JNICALL Java_com_defold_appodeal_AppodealBridge_nativeOnInit(
        JNIEnv* env, jclass, jboolean success, jstring reason)
    {
        CallbackEvent event;
        event.m_Channel = EVENT_INIT;
        event.m_Success = success == JNI_TRUE;
        event.m_Event = event.m_Success ? "initialized" : "init_failed";
        event.m_Error = JStringToString(env, reason);
        event.m_Rewarded = false;
        event.m_Amount = 0.0;
        EnqueueEvent(event);
    }

    extern "C" JNIEXPORT void JNICALL Java_com_defold_appodeal_AppodealBridge_nativeOnInterstitialEvent(
        JNIEnv* env, jclass, jstring event_name, jboolean success, jstring reason)
    {
        CallbackEvent event;
        event.m_Channel = EVENT_INTERSTITIAL;
        event.m_Success = success == JNI_TRUE;
        event.m_Event = JStringToString(env, event_name);
        event.m_Error = JStringToString(env, reason);
        event.m_Rewarded = false;
        event.m_Amount = 0.0;
        EnqueueEvent(event);
    }

    extern "C" JNIEXPORT void JNICALL Java_com_defold_appodeal_AppodealBridge_nativeOnRewardedEvent(
        JNIEnv* env, jclass, jstring event_name, jboolean success, jstring reason, jboolean rewarded, jdouble amount, jstring currency)
    {
        CallbackEvent event;
        event.m_Channel = EVENT_REWARDED;
        event.m_Success = success == JNI_TRUE;
        event.m_Event = JStringToString(env, event_name);
        event.m_Error = JStringToString(env, reason);
        event.m_Rewarded = rewarded == JNI_TRUE;
        event.m_Amount = amount;
        event.m_Currency = JStringToString(env, currency);
        EnqueueEvent(event);
    }
#endif

    static int LuaInit(lua_State* L)
    {
        DM_LUA_STACK_CHECK(L, 0);

        luaL_checktype(L, 1, LUA_TTABLE);
        if (!lua_isfunction(L, 2))
        {
            return luaL_error(L, "appodeal.init expects callback function");
        }

        lua_getfield(L, 1, "app_key");
        const char* app_key = lua_tostring(L, -1);
        lua_pop(L, 1);

        if (app_key == 0x0 || app_key[0] == '\0')
        {
            return luaL_error(L, "appodeal.init expects params.app_key as non-empty string");
        }

        bool testing = false;
        lua_getfield(L, 1, "testing");
        if (lua_isboolean(L, -1))
            testing = lua_toboolean(L, -1) != 0;
        lua_pop(L, 1);

        const char* log_level = "none";
        lua_getfield(L, 1, "log_level");
        if (lua_isstring(L, -1))
            log_level = lua_tostring(L, -1);
        lua_pop(L, 1);

        DestroyCallback(&g_Appodeal.m_InitCallback);
        g_Appodeal.m_InitCallback = dmScript::CreateCallback(L, 2);
        if (g_Appodeal.m_InitCallback == 0x0)
        {
            return luaL_error(L, "failed to create init callback");
        }

#if defined(DM_PLATFORM_ANDROID)
        if (!JavaInitialize(app_key, testing, log_level))
        {
            CallbackEvent event;
            event.m_Channel = EVENT_INIT;
            event.m_Event = "init_failed";
            event.m_Success = false;
            event.m_Error = "java_initialize_failed";
            event.m_Rewarded = false;
            event.m_Amount = 0.0;
            EnqueueEvent(event);
        }
#else
        CallbackEvent event;
        event.m_Channel = EVENT_INIT;
        event.m_Event = "init_failed";
        event.m_Success = false;
        event.m_Error = "android_only";
        event.m_Rewarded = false;
        event.m_Amount = 0.0;
        EnqueueEvent(event);
#endif
        return 0;
    }

    static int LuaIsInterstitialAvailable(lua_State* L)
    {
        DM_LUA_STACK_CHECK(L, 1);
        bool available = false;
#if defined(DM_PLATFORM_ANDROID)
        available = JavaIsInterstitialAvailable();
#endif
        lua_pushboolean(L, available ? 1 : 0);
        return 1;
    }

    static int LuaShowInterstitial(lua_State* L)
    {
        DM_LUA_STACK_CHECK(L, 0);

        if (!lua_isfunction(L, 1))
        {
            return luaL_error(L, "appodeal.show_interstitial expects callback function");
        }

        DestroyCallback(&g_Appodeal.m_InterstitialCallback);
        g_Appodeal.m_InterstitialCallback = dmScript::CreateCallback(L, 1);
        if (g_Appodeal.m_InterstitialCallback == 0x0)
        {
            return luaL_error(L, "failed to create interstitial callback");
        }

#if defined(DM_PLATFORM_ANDROID)
        if (!JavaShowInterstitial())
        {
            CallbackEvent event;
            event.m_Channel = EVENT_INTERSTITIAL;
            event.m_Event = "show_failed";
            event.m_Success = false;
            event.m_Error = "java_show_failed";
            event.m_Rewarded = false;
            event.m_Amount = 0.0;
            EnqueueEvent(event);
        }
#else
        CallbackEvent event;
        event.m_Channel = EVENT_INTERSTITIAL;
        event.m_Event = "show_failed";
        event.m_Success = false;
        event.m_Error = "android_only";
        event.m_Rewarded = false;
        event.m_Amount = 0.0;
        EnqueueEvent(event);
#endif
        return 0;
    }

    static int LuaIsRewardedAvailable(lua_State* L)
    {
        DM_LUA_STACK_CHECK(L, 1);
        bool available = false;
#if defined(DM_PLATFORM_ANDROID)
        available = JavaIsRewardedAvailable();
#endif
        lua_pushboolean(L, available ? 1 : 0);
        return 1;
    }

    static int LuaShowRewarded(lua_State* L)
    {
        DM_LUA_STACK_CHECK(L, 0);

        if (!lua_isfunction(L, 1))
        {
            return luaL_error(L, "appodeal.show_rewarded expects callback function");
        }

        DestroyCallback(&g_Appodeal.m_RewardedCallback);
        g_Appodeal.m_RewardedCallback = dmScript::CreateCallback(L, 1);
        if (g_Appodeal.m_RewardedCallback == 0x0)
        {
            return luaL_error(L, "failed to create rewarded callback");
        }

#if defined(DM_PLATFORM_ANDROID)
        if (!JavaShowRewarded())
        {
            CallbackEvent event;
            event.m_Channel = EVENT_REWARDED;
            event.m_Event = "show_failed";
            event.m_Success = false;
            event.m_Error = "java_show_failed";
            event.m_Rewarded = false;
            event.m_Amount = 0.0;
            EnqueueEvent(event);
        }
#else
        CallbackEvent event;
        event.m_Channel = EVENT_REWARDED;
        event.m_Event = "show_failed";
        event.m_Success = false;
        event.m_Error = "android_only";
        event.m_Rewarded = false;
        event.m_Amount = 0.0;
        EnqueueEvent(event);
#endif
        return 0;
    }

    static const luaL_reg Module_methods[] =
    {
        {"init", LuaInit},
        {"is_interstitial_available", LuaIsInterstitialAvailable},
        {"show_interstitial", LuaShowInterstitial},
        {"is_rewarded_available", LuaIsRewardedAvailable},
        {"show_rewarded", LuaShowRewarded},
        {0, 0}
    };

    static void LuaInitModule(lua_State* L)
    {
        luaL_register(L, LUA_MODULE_NAME, Module_methods);
        lua_pop(L, 1);
    }

    static dmExtension::Result AppodealAppInitialize(dmExtension::AppParams* params)
    {
        (void)params;
        g_Appodeal.m_InitCallback = 0x0;
        g_Appodeal.m_InterstitialCallback = 0x0;
        g_Appodeal.m_RewardedCallback = 0x0;
        return dmExtension::RESULT_OK;
    }

    static dmExtension::Result AppodealAppFinalize(dmExtension::AppParams* params)
    {
        (void)params;
        return dmExtension::RESULT_OK;
    }

    static dmExtension::Result AppodealInitialize(dmExtension::Params* params)
    {
        LuaInitModule(params->m_L);
        return dmExtension::RESULT_OK;
    }

    static dmExtension::Result AppodealUpdate(dmExtension::Params* params)
    {
        (void)params;
        FlushEvents();
        return dmExtension::RESULT_OK;
    }

    static void AppodealOnEvent(dmExtension::Params* params, const dmExtension::Event* event)
    {
        (void)params;
        (void)event;
    }

    static dmExtension::Result AppodealFinalize(dmExtension::Params* params)
    {
        (void)params;
        DestroyCallback(&g_Appodeal.m_InitCallback);
        DestroyCallback(&g_Appodeal.m_InterstitialCallback);
        DestroyCallback(&g_Appodeal.m_RewardedCallback);

#if defined(DM_PLATFORM_ANDROID)
        if (g_Appodeal.m_Jni.m_Class != 0x0)
        {
            JniEnvScope env_scope;
            if (env_scope.Attach())
            {
                env_scope.m_Env->DeleteGlobalRef(g_Appodeal.m_Jni.m_Class);
            }
            g_Appodeal.m_Jni.m_Class = 0x0;
        }
#endif
        return dmExtension::RESULT_OK;
    }

} // namespace

DM_DECLARE_EXTENSION(Appodeal, LUA_MODULE_NAME, AppodealAppInitialize, AppodealAppFinalize, AppodealInitialize, AppodealUpdate, AppodealOnEvent, AppodealFinalize)
