import React from "react";

interface ContentProps {
  children: React.ReactNode;
}

const Content: React.FC<ContentProps> = ({ children }) => {
  return <main className="min-h-0 min-w-0 flex-1 overflow-hidden">{children}</main>;
};

export default Content;
