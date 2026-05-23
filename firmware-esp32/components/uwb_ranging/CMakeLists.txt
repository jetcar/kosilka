idf_component_register(
    SRCS "ranging_sm.c" "trilateration.c" "anchor_config.c"
    INCLUDE_DIRS "include"
    REQUIRES uwb_driver
)
