/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 * vim: set ts=8 sts=4 et sw=4 tw=99:
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/*
 * JavaScript Debugging support - Script support
 */

#include "jsd.h"
#include "jsfriendapi.h"
#include "nsCxPusher.h"

using mozilla::AutoSafeJSContext;

/* Comment this out to disable (NT specific) dumping as we go */
/*
** #ifdef DEBUG      
** #define JSD_DUMP 1
** #endif            
*/

#define NOT_SET_YET -1

/***************************************************************************/

#ifdef DEBUG
void JSD_ASSERT_VALID_SCRIPT(JSDScript* jsdscript)
{
    JS_ASSERT(jsdscript);
    JS_ASSERT(jsdscript->script);
}
void JSD_ASSERT_VALID_EXEC_HOOK(JSDExecHook* jsdhook)
{
    JS_ASSERT(jsdhook);
    JS_ASSERT(jsdhook->hook);
}
#endif

static JSDScript*
_newJSDScript(JSDContext*  jsdc,
              JSContext    *cx,
              JSScript     *script_)
{
    JS::RootedScript script(cx, script_);
    if ( JS_GetScriptIsSelfHosted(script) )
        return nullptr;

    JSDScript*  jsdscript;
    unsigned     lineno;
    const char* raw_filename;

    JS_ASSERT(JSD_SCRIPTS_LOCKED(jsdc));

    /* these are inlined javascript: urls and we can't handle them now */
    lineno = (unsigned) JS_GetScriptBaseLineNumber(cx, script);
    if( lineno == 0 )
        return nullptr;

    jsdscript = (JSDScript*) calloc(1, sizeof(JSDScript));
    if( ! jsdscript )
        return nullptr;

    raw_filename = JS_GetScriptFilename(cx,script);

    JS_HashTableAdd(jsdc->scriptsTable, (void *)script, (void *)jsdscript);
    JS_APPEND_LINK(&jsdscript->links, &jsdc->scripts);
    jsdscript->jsdc         = jsdc;
    jsdscript->script       = script;  
    jsdscript->lineBase     = lineno;
    jsdscript->lineExtent   = (unsigned)NOT_SET_YET;
    jsdscript->data         = nullptr;
    jsdscript->url          = (char*) jsd_BuildNormalizedURL(raw_filename);

    JS_INIT_CLIST(&jsdscript->hooks);
    
    return jsdscript;
}           

static void 
_destroyJSDScript(JSDContext*  jsdc,
                  JSDScript*   jsdscript)
{
    JS_ASSERT(JSD_SCRIPTS_LOCKED(jsdc));

    /* destroy all hooks */
    jsd_ClearAllExecutionHooksForScript(jsdc, jsdscript);

    JS_REMOVE_LINK(&jsdscript->links);
    if(jsdscript->url)
        free(jsdscript->url);

    if (jsdscript->profileData)
        free(jsdscript->profileData);
    
    free(jsdscript);
}

/***************************************************************************/

#ifdef JSD_DUMP
#ifndef XP_WIN
void
OutputDebugString (char *buf)
{
    fprintf (stderr, "%s", buf);
}
#endif

static void
_dumpJSDScript(JSDContext* jsdc, JSDScript* jsdscript, const char* leadingtext)
{
    const char* name;
    JSString* fun;
    unsigned base;
    unsigned extent;
    char Buf[256];
    size_t n;

    name   = jsd_GetScriptFilename(jsdc, jsdscript);
    fun    = jsd_GetScriptFunctionId(jsdc, jsdscript);
    base   = jsd_GetScriptBaseLineNumber(jsdc, jsdscript);
    extent = jsd_GetScriptLineExtent(jsdc, jsdscript);
    n = size_t(snprintf(Buf, sizeof(Buf), "%sscript=%08X, %s, ",
                        leadingtext, (unsigned) jsdscript->script,
                        name ? name : "no URL"));
    if (n + 1 < sizeof(Buf)) {
        if (fun) {
            n += size_t(snprintf(Buf + n, sizeof(Buf) - n, "%s", "no fun"));
        } else {
            n += JS_PutEscapedFlatString(Buf + n, sizeof(Buf) - n,
                                         JS_ASSERT_STRING_IS_FLAT(fun), 0);
            Buf[sizeof(Buf) - 1] = '\0';
        }
        if (n + 1 < sizeof(Buf))
            snprintf(Buf + n, sizeof(Buf) - n, ", %d-%d\n", base, base + extent - 1);
    }
    OutputDebugString( Buf );
}

static void
_dumpJSDScriptList( JSDContext* jsdc )
{
    JSDScript* iterp = nullptr;
    JSDScript* jsdscript = nullptr;
    
    OutputDebugString( "*** JSDScriptDump\n" );
    while( nullptr != (jsdscript = jsd_IterateScripts(jsdc, &iterp)) )
        _dumpJSDScript( jsdc, jsdscript, "  script: " );
}
#endif /* JSD_DUMP */

/***************************************************************************/
static JSHashNumber
jsd_hash_script(const void *key)
{
    return ((JSHashNumber)(ptrdiff_t) key) >> 2; /* help lame MSVC1.5 on Win16 */
}

static void *
jsd_alloc_script_table(void *priv, size_t size)
{
    return malloc(size);
}

static void
jsd_free_script_table(void *priv, void *item, size_t size)
{
    free(item);
}

static JSHashEntry *
jsd_alloc_script_entry(void *priv, const void *item)
{
    return (JSHashEntry*) malloc(sizeof(JSHashEntry));
}

static void
jsd_free_script_entry(void *priv, JSHashEntry *he, unsigned flag)
{
    if (flag == HT_FREE_ENTRY)
    {
        _destroyJSDScript((JSDContext*) priv, (JSDScript*) he->value);
        free(he);
    }
}

static JSHashAllocOps script_alloc_ops = {
    jsd_alloc_script_table, jsd_free_script_table,
    jsd_alloc_script_entry, jsd_free_script_entry
};

#ifndef JSD_SCRIPT_HASH_SIZE
#define JSD_SCRIPT_HASH_SIZE 1024
#endif

bool
jsd_InitScriptManager(JSDContext* jsdc)
{
    JS_INIT_CLIST(&jsdc->scripts);
    jsdc->scriptsTable = JS_NewHashTable(JSD_SCRIPT_HASH_SIZE, jsd_hash_script,
                                         JS_CompareValues, JS_CompareValues,
                                         &script_alloc_ops, (void*) jsdc);
    return !!jsdc->scriptsTable;
}

void
jsd_DestroyScriptManager(JSDContext* jsdc)
{
    JSD_LOCK_SCRIPTS(jsdc);
    if (jsdc->scriptsTable)
        JS_HashTableDestroy(jsdc->scriptsTable);
    JSD_UNLOCK_SCRIPTS(jsdc);
}

JSDScript*
jsd_FindJSDScript( JSDContext*  jsdc,
                   JSScript     *script )
{
    JS_ASSERT(JSD_SCRIPTS_LOCKED(jsdc));
    return (JSDScript*) JS_HashTableLookup(jsdc->scriptsTable, (void *)script);
}

JSDScript *
jsd_FindOrCreateJSDScript(JSDContext    *jsdc,
                          JSContext     *cx,
                          JSScript      *script_,
                          JSAbstractFramePtr frame)
{
    JS::RootedScript script(cx, script_);
    JSDScript *jsdscript;
    JS_ASSERT(JSD_SCRIPTS_LOCKED(jsdc));

    jsdscript = jsd_FindJSDScript(jsdc, script);
    if (jsdscript)
        return jsdscript;

    /* Fallback for unknown scripts: create a new script. */
    if (!frame) {
        JSBrokenFrameIterator iter(cx);
        if (!iter.done())
            frame = iter.abstractFramePtr();
    }
    if (frame)
        jsdscript = _newJSDScript(jsdc, cx, script);

    return jsdscript;
}

JSDProfileData*
jsd_GetScriptProfileData(JSDContext* jsdc, JSDScript *script)
{
    if (!script->profileData)
        script->profileData = (JSDProfileData*)calloc(1, sizeof(JSDProfileData));

    return script->profileData;
}

uint32_t
jsd_GetScriptFlags(JSDContext *jsdc, JSDScript *script)
{
    return script->flags;
}

void
jsd_SetScriptFlags(JSDContext *jsdc, JSDScript *script, uint32_t flags)
{
    script->flags = flags;
}

unsigned
jsd_GetScriptCallCount(JSDContext* jsdc, JSDScript *script)
{
    if (script->profileData)
        return script->profileData->callCount;

    return 0;
}

unsigned
jsd_GetScriptMaxRecurseDepth(JSDContext* jsdc, JSDScript *script)
{
    if (script->profileData)
        return script->profileData->maxRecurseDepth;

    return 0;
}

double
jsd_GetScriptMinExecutionTime(JSDContext* jsdc, JSDScript *script)
{
    if (script->profileData)
        return script->profileData->minExecutionTime;

    return 0.0;
}

double
jsd_GetScriptMaxExecutionTime(JSDContext* jsdc, JSDScript *script)
{
    if (script->profileData)
        return script->profileData->maxExecutionTime;

    return 0.0;
}

double
jsd_GetScriptTotalExecutionTime(JSDContext* jsdc, JSDScript *script)
{
    if (script->profileData)
        return script->profileData->totalExecutionTime;

    return 0.0;
}

double
jsd_GetScriptMinOwnExecutionTime(JSDContext* jsdc, JSDScript *script)
{
    if (script->profileData)
        return script->profileData->minOwnExecutionTime;

    return 0.0;
}

double
jsd_GetScriptMaxOwnExecutionTime(JSDContext* jsdc, JSDScript *script)
{
    if (script->profileData)
        return script->profileData->maxOwnExecutionTime;

    return 0.0;
}

double
jsd_GetScriptTotalOwnExecutionTime(JSDContext* jsdc, JSDScript *script)
{
    if (script->profileData)
        return script->profileData->totalOwnExecutionTime;

    return 0.0;
}

void
jsd_ClearScriptProfileData(JSDContext* jsdc, JSDScript *script)
{
    if (script->profileData)
    {
        free(script->profileData);
        script->profileData = nullptr;
    }
}    

JSScript *
jsd_GetJSScript (JSDContext *jsdc, JSDScript *script)
{
    return script->script;
}

JSFunction *
jsd_GetJSFunction (JSDContext *jsdc, JSDScript *script)
{
    AutoSafeJSContext cx; // NB: Actually unused.
    return JS_GetScriptFunction(cx, script->script);
}

JSDScript*
jsd_IterateScripts(JSDContext* jsdc, JSDScript **iterp)
{
    JSDScript *jsdscript = *iterp;
    
    JS_ASSERT(JSD_SCRIPTS_LOCKED(jsdc));

    if( !jsdscript )
        jsdscript = (JSDScript *)jsdc->scripts.next;
    if( jsdscript == (JSDScript *)&jsdc->scripts )
        return nullptr;
    *iterp = (JSDScript*) jsdscript->links.next;
    return jsdscript;
}

void *
jsd_SetScriptPrivate(JSDScript *jsdscript, void *data)
{
    void *rval = jsdscript->data;
    jsdscript->data = data;
    return rval;
}

void *
jsd_GetScriptPrivate(JSDScript *jsdscript)
{
    return jsdscript->data;
}

bool
jsd_IsActiveScript(JSDContext* jsdc, JSDScript *jsdscript)
{
    JSDScript *current;

    JS_ASSERT(JSD_SCRIPTS_LOCKED(jsdc));

    for( current = (JSDScript *)jsdc->scripts.next;
         current != (JSDScript *)&jsdc->scripts;
         current = (JSDScript *)current->links.next )
    {
        if(jsdscript == current)
            return true;
    }
    return false;
}        

const char*
jsd_GetScriptFilename(JSDContext* jsdc, JSDScript *jsdscript)
{
    return jsdscript->url;
}

JSString*
jsd_GetScriptFunctionId(JSDContext* jsdc, JSDScript *jsdscript)
{
    JSString* str;
    JSFunction *fun = jsd_GetJSFunction(jsdc, jsdscript);

    if( ! fun )
        return nullptr;
    str = JS_GetFunctionId(fun);

    /* For compatibility we return "anonymous", not an empty string here. */
    return str ? str : JS_GetAnonymousString(jsdc->jsrt);
}

unsigned
jsd_GetScriptBaseLineNumber(JSDContext* jsdc, JSDScript *jsdscript)
{
    return jsdscript->lineBase;
}

unsigned
jsd_GetScriptLineExtent(JSDContext* jsdc, JSDScript *jsdscript)
{
    AutoSafeJSContext cx;
    JSAutoCompartment ac(cx, jsdc->glob); // Just in case.
    if( NOT_SET_YET == (int)jsdscript->lineExtent )
        jsdscript->lineExtent = JS_GetScriptLineExtent(cx, jsdscript->script);
    return jsdscript->lineExtent;
}

uintptr_t
jsd_GetClosestPC(JSDContext* jsdc, JSDScript* jsdscript, unsigned line)
{
    uintptr_t pc;

    if( !jsdscript )
        return 0;

    AutoSafeJSContext cx;
    JSAutoCompartment ac(cx, jsdscript->script);
    pc = (uintptr_t) JS_LineNumberToPC(cx, jsdscript->script, line );
    return pc;
}

unsigned
jsd_GetClosestLine(JSDContext* jsdc, JSDScript* jsdscript, uintptr_t pc)
{
    unsigned first = jsdscript->lineBase;
    unsigned last = first + jsd_GetScriptLineExtent(jsdc, jsdscript) - 1;
    unsigned line = 0;

    if (pc) {
        AutoSafeJSContext cx;
        JSAutoCompartment ac(cx, jsdscript->script);
        line = JS_PCToLineNumber(cx, jsdscript->script, (jsbytecode*)pc);
    }

    if( line < first )
        return first;
    if( line > last )
        return last;

    return line;    
}

bool
jsd_GetLinePCs(JSDContext* jsdc, JSDScript* jsdscript,
               unsigned startLine, unsigned maxLines,
               unsigned* count, unsigned** retLines, uintptr_t** retPCs)
{
    unsigned first = jsdscript->lineBase;
    unsigned last = first + jsd_GetScriptLineExtent(jsdc, jsdscript) - 1;
    bool ok;
    jsbytecode **pcs;
    unsigned i;

    if (last < startLine)
        return true;

    AutoSafeJSContext cx;
    JSAutoCompartment ac(cx, jsdscript->script);

    ok = JS_GetLinePCs(cx, jsdscript->script,
                       startLine, maxLines,
                       count, retLines, &pcs);

    if (ok) {
        if (retPCs) {
            for (i = 0; i < *count; ++i) {
                (*retPCs)[i] = (*pcs)[i];
            }
        }

        JS_free(cx, pcs);
    }

    return ok;
}

bool
jsd_SetScriptHook(JSDContext* jsdc, JSD_ScriptHookProc hook, void* callerdata)
{
    JSD_LOCK();
    jsdc->scriptHook = hook;
    jsdc->scriptHookData = callerdata;
    JSD_UNLOCK();
    return true;
}

bool
jsd_GetScriptHook(JSDContext* jsdc, JSD_ScriptHookProc* hook, void** callerdata)
{
    JSD_LOCK();
    if( hook )
        *hook = jsdc->scriptHook;
    if( callerdata )
        *callerdata = jsdc->scriptHookData;
    JSD_UNLOCK();
    return true;
}    

bool
jsd_EnableSingleStepInterrupts(JSDContext* jsdc, JSDScript* jsdscript, bool enable)
{
    bool rv;
    AutoSafeJSContext cx;
    JSAutoCompartment ac(cx, jsdscript->script);
    JSD_LOCK();
    rv = JS_SetSingleStepMode(cx, jsdscript->script, enable);
    JSD_UNLOCK();
    return rv;
}


/***************************************************************************/

void
jsd_NewScriptHookProc( 
                JSContext   *cx,
                const char  *filename,      /* URL this script loads from */
                unsigned       lineno,         /* line where this script starts */
                JSScript    *script,
                JSFunction  *fun,                
                void*       callerdata )
{
    JSDScript* jsdscript = nullptr;
    JSDContext* jsdc = (JSDContext*) callerdata;
    JSD_ScriptHookProc      hook;
    void*                   hookData;
    
    JSD_ASSERT_VALID_CONTEXT(jsdc);

    if( JSD_IS_DANGEROUS_THREAD(jsdc) )
        return;
    
    JSD_LOCK_SCRIPTS(jsdc);
    jsdscript = _newJSDScript(jsdc, cx, script);
    JSD_UNLOCK_SCRIPTS(jsdc);
    if( ! jsdscript )
        return;

#ifdef JSD_DUMP
    JSD_LOCK_SCRIPTS(jsdc);
    _dumpJSDScript(jsdc, jsdscript, "***NEW Script: ");
    _dumpJSDScriptList( jsdc );
    JSD_UNLOCK_SCRIPTS(jsdc);
#endif /* JSD_DUMP */

    /* local in case jsdc->scriptHook gets cleared on another thread */
    JSD_LOCK();
    hook = jsdc->scriptHook;
    if( hook )
        jsdscript->flags = jsdscript->flags | JSD_SCRIPT_CALL_DESTROY_HOOK_BIT;
    hookData = jsdc->scriptHookData;
    JSD_UNLOCK();

    if( hook )
        hook(jsdc, jsdscript, true, hookData);
}

void
jsd_DestroyScriptHookProc( 
                JSFreeOp    *fop,
                JSScript    *script_,
                void*       callerdata )
{
    JSDScript* jsdscript = nullptr;
    JSDContext* jsdc = (JSDContext*) callerdata;
    // NB: We're called during GC, so we can't push a cx. Root directly with
    // the runtime.
    JS::RootedScript script(jsdc->jsrt, script_);
    JSD_ScriptHookProc      hook;
    void*                   hookData;

    JSD_ASSERT_VALID_CONTEXT(jsdc);

    if( JSD_IS_DANGEROUS_THREAD(jsdc) )
        return;

    JSD_LOCK_SCRIPTS(jsdc);
    jsdscript = jsd_FindJSDScript(jsdc, script);
    JSD_UNLOCK_SCRIPTS(jsdc);

    if( ! jsdscript )
        return;

#ifdef JSD_DUMP
    JSD_LOCK_SCRIPTS(jsdc);
    _dumpJSDScript(jsdc, jsdscript, "***DESTROY Script: ");
    JSD_UNLOCK_SCRIPTS(jsdc);
#endif /* JSD_DUMP */

    /* local in case hook gets cleared on another thread */
    JSD_LOCK();
    hook = (jsdscript->flags & JSD_SCRIPT_CALL_DESTROY_HOOK_BIT) ? jsdc->scriptHook
                                                                 : nullptr;
    hookData = jsdc->scriptHookData;
    JSD_UNLOCK();

    if( hook )
        hook(jsdc, jsdscript, false, hookData);

    JSD_LOCK_SCRIPTS(jsdc);
    JS_HashTableRemove(jsdc->scriptsTable, (void *)script);
    JSD_UNLOCK_SCRIPTS(jsdc);

#ifdef JSD_DUMP
    JSD_LOCK_SCRIPTS(jsdc);
    _dumpJSDScriptList(jsdc);
    JSD_UNLOCK_SCRIPTS(jsdc);
#endif /* JSD_DUMP */
}                


/***************************************************************************/

static JSDExecHook*
_findHook(JSDContext* jsdc, JSDScript* jsdscript, uintptr_t pc)
{
    JSDExecHook* jsdhook;
    JSCList* list = &jsdscript->hooks;

    for( jsdhook = (JSDExecHook*)list->next;
         jsdhook != (JSDExecHook*)list;
         jsdhook = (JSDExecHook*)jsdhook->links.next )
    {
        if (jsdhook->pc == pc)
            return jsdhook;
    }
    return nullptr;
}

static bool
_isActiveHook(JSDContext* jsdc, JSScript *script, JSDExecHook* jsdhook)
{
    JSDExecHook* current;
    JSCList* list;
    JSDScript* jsdscript;

    JSD_LOCK_SCRIPTS(jsdc);
    jsdscript = jsd_FindJSDScript(jsdc, script);
    if( ! jsdscript)
    {
        JSD_UNLOCK_SCRIPTS(jsdc);
        return false;
    }

    list = &jsdscript->hooks;

    for( current = (JSDExecHook*)list->next;
         current != (JSDExecHook*)list;
         current = (JSDExecHook*)current->links.next )
    {
        if(current == jsdhook)
        {
            JSD_UNLOCK_SCRIPTS(jsdc);
            return true;
        }
    }
    JSD_UNLOCK_SCRIPTS(jsdc);
    return false;
}


JSTrapStatus
jsd_TrapHandler(JSContext *cx, JSScript *script_, jsbytecode *pc, jsval *rval,
                jsval closure)
{
    JS::RootedScript script(cx, script_);
    JSDExecHook* jsdhook = (JSDExecHook*) JSVAL_TO_PRIVATE(closure);
    JSD_ExecutionHookProc hook;
    void* hookData;
    JSDContext*  jsdc;

    JSD_LOCK();

    if( nullptr == (jsdc = jsd_JSDContextForJSContext(cx)) ||
        ! _isActiveHook(jsdc, script, jsdhook) )
    {
        JSD_UNLOCK();
        return JSTRAP_CONTINUE;
    }

    JSD_ASSERT_VALID_EXEC_HOOK(jsdhook);
    JS_ASSERT(!jsdhook->pc || jsdhook->pc == (uintptr_t)pc);
    JS_ASSERT(jsdhook->jsdscript->script == script);
    JS_ASSERT(jsdhook->jsdscript->jsdc == jsdc);

    hook = jsdhook->hook;
    hookData = jsdhook->callerdata;

    /* do not use jsdhook-> after this point */
    JSD_UNLOCK();

    if( ! jsdc || ! jsdc->inited )
        return JSTRAP_CONTINUE;

    if( JSD_IS_DANGEROUS_THREAD(jsdc) )
        return JSTRAP_CONTINUE;

    return jsd_CallExecutionHook(jsdc, cx, JSD_HOOK_BREAKPOINT,
                                 hook, hookData, rval);
}



bool
jsd_SetExecutionHook(JSDContext*           jsdc, 
                     JSDScript*            jsdscript,
                     uintptr_t             pc,
                     JSD_ExecutionHookProc hook,
                     void*                 callerdata)
{
    JSDExecHook* jsdhook;
    bool rv;

    JSD_LOCK();
    if( ! hook )
    {
        jsd_ClearExecutionHook(jsdc, jsdscript, pc);
        JSD_UNLOCK();
        return true;
    }

    jsdhook = _findHook(jsdc, jsdscript, pc);
    if( jsdhook )
    {
        jsdhook->hook       = hook;
        jsdhook->callerdata = callerdata;
        JSD_UNLOCK();
        return true;
    }
    /* else... */

    jsdhook = (JSDExecHook*)calloc(1, sizeof(JSDExecHook));
    if( ! jsdhook ) {
        JSD_UNLOCK();
        return false;
    }
    jsdhook->jsdscript  = jsdscript;
    jsdhook->pc         = pc;
    jsdhook->hook       = hook;
    jsdhook->callerdata = callerdata;

    {
        AutoSafeJSContext cx;
        JSAutoCompartment ac(cx, jsdscript->script);
        rv = JS_SetTrap(cx, jsdscript->script, 
                        (jsbytecode*)pc, jsd_TrapHandler,
                        PRIVATE_TO_JSVAL(jsdhook));
    }

    if ( ! rv ) {
        free(jsdhook);
        JSD_UNLOCK();
        return false;
    }

    JS_APPEND_LINK(&jsdhook->links, &jsdscript->hooks);
    JSD_UNLOCK();

    return true;
}

bool
jsd_ClearExecutionHook(JSDContext*           jsdc, 
                       JSDScript*            jsdscript,
                       uintptr_t             pc)
{
    JSDExecHook* jsdhook;

    JSD_LOCK();

    jsdhook = _findHook(jsdc, jsdscript, pc);
    if( ! jsdhook )
    {
        JSD_UNLOCK();
        return false;
    }

    {
        AutoSafeJSContext cx;
        JSAutoCompartment ac(cx, jsdscript->script);
        JS_ClearTrap(cx, jsdscript->script, 
                     (jsbytecode*)pc, nullptr, nullptr);
    }

    JS_REMOVE_LINK(&jsdhook->links);
    free(jsdhook);

    JSD_UNLOCK();
    return true;
}

bool
jsd_ClearAllExecutionHooksForScript(JSDContext* jsdc, JSDScript* jsdscript)
{
    JSDExecHook* jsdhook;
    JSCList* list = &jsdscript->hooks;
    JSD_LOCK();

    while( (JSDExecHook*)list != (jsdhook = (JSDExecHook*)list->next) )
    {
        JS_REMOVE_LINK(&jsdhook->links);
        free(jsdhook);
    }

    JS_ClearScriptTraps(jsdc->jsrt, jsdscript->script);
    JSD_UNLOCK();

    return true;
}

bool
jsd_ClearAllExecutionHooks(JSDContext* jsdc)
{
    JSDScript* jsdscript;
    JSDScript* iterp = nullptr;

    JSD_LOCK();
    while( nullptr != (jsdscript = jsd_IterateScripts(jsdc, &iterp)) )
        jsd_ClearAllExecutionHooksForScript(jsdc, jsdscript);
    JSD_UNLOCK();
    return true;
}

void
jsd_ScriptCreated(JSDContext* jsdc,
                  JSContext   *cx,
                  const char  *filename,    /* URL this script loads from */
                  unsigned       lineno,       /* line where this script starts */
                  JSScript    *script,
                  JSFunction  *fun)
{
    jsd_NewScriptHookProc(cx, filename, lineno, script, fun, jsdc);
}

void
jsd_ScriptDestroyed(JSDContext* jsdc,
                    JSFreeOp    *fop,
                    JSScript    *script)
{
    jsd_DestroyScriptHookProc(fop, script, jsdc);
}
