import warnings

# hi-ml-multimodal import timm.models.layers (đã deprecated); cảnh báo vô hại.
warnings.filterwarnings("ignore", category=FutureWarning, module=r"timm\..*")
