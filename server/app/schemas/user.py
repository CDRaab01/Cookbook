import uuid

from pydantic import BaseModel


class UserOut(BaseModel):
    id: uuid.UUID
    name: str
    email: str

    model_config = {"from_attributes": True}
