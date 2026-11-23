Compile-only stand-ins for the few Simple Voice Chat API interfaces Evade's replay voice support uses, with the
same names and signatures as the real API (checked against voicechat-api 2.6). They are never packaged: at runtime
the real classes come from Simple Voice Chat itself, and Evade only touches them when that mod is installed (its
"voicechat" entrypoint is only called by Simple Voice Chat).
