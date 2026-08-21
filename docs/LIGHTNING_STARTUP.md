# Lightning camera startup

The startup path prioritizes the first normal rear preview. Front-camera, advertised AUX, logical/physical and NDK RAW metadata discovery are background work and may overlap camera opening. Metadata reads are bounded rather than unbounded so vendor camera-service binder threads are not flooded. Hidden numeric AUX probing remains a later compatibility pass when a normal RAW route already exists.
