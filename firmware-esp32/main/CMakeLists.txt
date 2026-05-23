idf_component_register(
    SRCS "main.c"
    INCLUDE_DIRS "."
    REQUIRES usb_transport protocol uwb_ranging uwb_driver
)
